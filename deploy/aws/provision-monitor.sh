#!/usr/bin/env bash
# Provisions the App Monitor server on EC2. Run in AWS CloudShell from a folder holding this script,
# monitor-userdata.sh, fyntune-agent.jar and either app-monitor-server.jar or its split parts (app-monitor-server.jar.part-*):
#   AWS_REGION=ap-southeast-2 bash provision-monitor.sh
# Creates (once): a private S3 bucket for the jars, security group app-monitor-sg (80/443 open, SSH from EC2 Instance
# Connect only), an Elastic IP, and an Ubuntu 24.04 t3.small with 20 GB gp3. Re-running reuses what exists.
set -euo pipefail
R=${AWS_REGION:-ap-southeast-2}
NAME=app-monitor
TYPE=${INSTANCE_TYPE:-t3.small}
export AWS_DEFAULT_REGION=$R
cd "$(dirname "$0")"

ACCT=$(aws sts get-caller-identity --query Account --output text)
BUCKET=$NAME-artifacts-$ACCT-$R

echo "==> Jars"
if [ ! -f app-monitor-server.jar ]; then cat app-monitor-server.jar.part-* > app-monitor-server.jar; fi
sha256sum app-monitor-server.jar fyntune-agent.jar
if ! aws s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  aws s3api create-bucket --bucket "$BUCKET" --create-bucket-configuration LocationConstraint="$R" >/dev/null
  aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
fi
aws s3 cp app-monitor-server.jar "s3://$BUCKET/app-monitor-server.jar" --only-show-errors
aws s3 cp fyntune-agent.jar "s3://$BUCKET/fyntune-agent.jar" --only-show-errors
SERVER_URL=$(aws s3 presign "s3://$BUCKET/app-monitor-server.jar" --expires-in 43200)
AGENT_URL=$(aws s3 presign "s3://$BUCKET/fyntune-agent.jar" --expires-in 43200)

echo "==> Network"
VPC=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
SUBNET=$(aws ec2 describe-subnets --filters Name=vpc-id,Values="$VPC" Name=default-for-az,Values=true \
  --query 'Subnets[0].SubnetId' --output text)
SG=$(aws ec2 describe-security-groups --filters Name=group-name,Values=$NAME-sg Name=vpc-id,Values="$VPC" \
  --query 'SecurityGroups[0].GroupId' --output text)
if [ "$SG" = "None" ]; then
  SG=$(aws ec2 create-security-group --group-name $NAME-sg --description "App Monitor web + agent ingest" \
    --vpc-id "$VPC" --query GroupId --output text)
  EIC=$(aws ec2 describe-managed-prefix-lists --filters Name=prefix-list-name,Values=com.amazonaws.$R.ec2-instance-connect \
    --query 'PrefixLists[0].PrefixListId' --output text)
  aws ec2 authorize-security-group-ingress --group-id "$SG" --ip-permissions \
    "IpProtocol=tcp,FromPort=22,ToPort=22,PrefixListIds=[{PrefixListId=$EIC,Description=ec2-instance-connect}]" \
    "IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0}]" \
    "IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0}]" >/dev/null
fi

EIP=$(aws ec2 describe-addresses --filters Name=tag:Name,Values=$NAME --query 'Addresses[0].PublicIp' --output text)
if [ "$EIP" = "None" ]; then
  aws ec2 allocate-address --domain vpc --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=$NAME}]" >/dev/null
  EIP=$(aws ec2 describe-addresses --filters Name=tag:Name,Values=$NAME --query 'Addresses[0].PublicIp' --output text)
fi
ALLOC=$(aws ec2 describe-addresses --filters Name=tag:Name,Values=$NAME --query 'Addresses[0].AllocationId' --output text)
SITE="${EIP//./-}.sslip.io"

echo "==> Instance"
IID=$(aws ec2 describe-instances --filters Name=tag:Name,Values=$NAME \
  Name=instance-state-name,Values=pending,running,stopping,stopped --query 'Reservations[0].Instances[0].InstanceId' --output text)
if [ "$IID" = "None" ]; then
  esc() { printf '%s' "$1" | sed -e 's/[&|\\]/\\&/g'; }   # presigned URLs contain '&', special in sed replacements
  sed -e "s|^SITE=.*|SITE=\"$SITE\"|" -e "s|^SERVER_JAR_URL=.*|SERVER_JAR_URL=\"$(esc "$SERVER_URL")\"|" \
      -e "s|^AGENT_JAR_URL=.*|AGENT_JAR_URL=\"$(esc "$AGENT_URL")\"|" monitor-userdata.sh > userdata.rendered.sh
  AMI=$(aws ssm get-parameter --name /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id \
    --query Parameter.Value --output text)
  IID=$(aws ec2 run-instances --image-id "$AMI" --instance-type "$TYPE" \
    --subnet-id "$SUBNET" --security-group-ids "$SG" --associate-public-ip-address \
    --block-device-mappings 'DeviceName=/dev/sda1,Ebs={VolumeSize=20,VolumeType=gp3,Encrypted=true}' \
    --metadata-options HttpTokens=required,HttpPutResponseHopLimit=2 \
    --user-data file://userdata.rendered.sh \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$NAME}]" \
    --query 'Instances[0].InstanceId' --output text)
  rm -f userdata.rendered.sh
fi
aws ec2 wait instance-running --instance-ids "$IID"
aws ec2 associate-address --instance-id "$IID" --allocation-id "$ALLOC" >/dev/null

echo
echo "INSTANCE=$IID"
echo "EIP=$EIP"
echo "URL=https://$SITE"
echo "PROVISION_DONE"

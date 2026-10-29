-- What each agent's server is (OS, CPU, memory, disks, addresses): the latest one the agent sent, about hourly.
ALTER TABLE agent ADD COLUMN spec JSONB;
ALTER TABLE agent ADD COLUMN spec_at TIMESTAMPTZ;

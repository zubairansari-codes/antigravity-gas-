-- Add telephony provider column to agencies table
ALTER TABLE agencies
ADD COLUMN telephony_provider VARCHAR(20) DEFAULT 'TWILIO';

-- Update existing agencies to use Twilio as default
UPDATE agencies
SET telephony_provider = 'TWILIO'
WHERE telephony_provider IS NULL;

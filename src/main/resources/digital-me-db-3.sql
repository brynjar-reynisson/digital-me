-- Migration to update absolute file paths after directory move
UPDATE MCP_EMBEDDING 
SET FILE_PATH = REPLACE(FILE_PATH, 'C:\Users\Lenovo\DigitalBrynjar', 'C:\Users\Lenovo\DigitalMe');

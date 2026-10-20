-- Apply only through the protected 007 upgrader to an exact 006 baseline.
-- Additive only: existing doctor rows keep display_name/department; title and bio start NULL.
ALTER TABLE doctor_profile
    ADD COLUMN title VARCHAR(100) NULL AFTER display_name,
    ADD COLUMN bio VARCHAR(1000) NULL AFTER title;

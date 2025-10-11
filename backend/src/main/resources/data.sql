-- Populate jurisdictions (Harlem hierarchy)
INSERT INTO jurisdictions (jurisdiction_id, name, polygon, parent_jurisdiction_id, created_at)
VALUES 
    ('00000000-0000-0000-0000-000000000001', 'Harlem', NULL, NULL, NOW()),
    ('00000000-0000-0000-0000-000000000002', 'Uptown Harlem', NULL, '00000000-0000-0000-0000-000000000001', NOW()),
    ('00000000-0000-0000-0000-000000000003', 'Downtown Harlem', NULL, '00000000-0000-0000-0000-000000000001', NOW());

-- Populate genres (music basics)
INSERT INTO genres (genre_id, name, created_at)
VALUES 
    ('00000000-0000-0000-0000-000000000101', 'Hip-Hop', NOW()),
    ('00000000-0000-0000-0000-000000000102', 'R&B', NOW()),
    ('00000000-0000-0000-0000-000000000103', 'Jazz', NOW());

-- Populate voting_intervals
INSERT INTO voting_intervals (interval_id, name, duration_days, created_at)
VALUES 
    ('00000000-0000-0000-0000-000000000201', 'Daily', 1, NOW()),
    ('00000000-0000-0000-0000-000000000202', 'Weekly', 7, NOW()),
    ('00000000-0000-0000-0000-000000000203', 'Monthly', 30, NOW()),
    ('00000000-0000-0000-0000-000000000204', 'Quarterly', 90, NOW()),
    ('00000000-0000-0000-0000-000000000205', 'Annual', 365, NOW());
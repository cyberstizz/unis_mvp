-- Make sure UUIDs are available
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Ensure unique names so we can upsert by name safely
CREATE UNIQUE INDEX IF NOT EXISTS idx_genres_name ON genres(name);
CREATE UNIQUE INDEX IF NOT EXISTS idx_jurisdictions_name ON jurisdictions(name);

-- Upsert the three MVP genres
INSERT INTO genres (name)
VALUES ('Rap/Hip-Hop'), ('Rock'), ('Pop')
ON CONFLICT (name) DO NOTHING;

-- Upsert the four jurisdictions
INSERT INTO jurisdictions (name, polygon, parent_jurisdiction_id)
VALUES 
  ('UNIS (sitewide)', NULL, NULL),
  ('Harlem', NULL, NULL),
  ('Uptown Harlem', NULL, NULL),
  ('Downtown Harlem', NULL, NULL)
ON CONFLICT (name) DO NOTHING;

-- Wire up the hierarchy:
-- UNIS (sitewide) -> Harlem -> (Uptown, Downtown)
UPDATE jurisdictions h
SET parent_jurisdiction_id = u.jurisdiction_id
FROM jurisdictions u
WHERE h.name = 'Harlem'
  AND u.name = 'UNIS (sitewide)';

UPDATE jurisdictions c
SET parent_jurisdiction_id = h.jurisdiction_id
FROM jurisdictions h
WHERE c.name IN ('Uptown Harlem','Downtown Harlem')
  AND h.name = 'Harlem';

-- === Seed 20 artists and one song per artist ===
WITH g AS (
  SELECT name AS genre_name, genre_id FROM genres
  WHERE name IN ('Rap/Hip-Hop','Rock','Pop')
),
j AS (
  SELECT name AS jur_name, jurisdiction_id FROM jurisdictions
  WHERE name IN ('Uptown Harlem','Downtown Harlem','Harlem')
),
seed(username, email, genre_name, jur_name) AS (
  VALUES
  -- Rap/Hip-Hop (7)
  ('SnubCheezy','snubcheezy@example.com','Rap/Hip-Hop','Uptown Harlem'),
  ('FirstClass','firstclass@example.com','Rap/Hip-Hop','Downtown Harlem'),
  ('CoolC','coolc@example.com','Rap/Hip-Hop','Harlem'),
  ('TechChambers','techchambers@example.com','Rap/Hip-Hop','Uptown Harlem'),
  ('Stizz','stizz@example.com','Rap/Hip-Hop','Downtown Harlem'),
  ('KevHutch','kevhutch@example.com','Rap/Hip-Hop','Harlem'),
  ('Niceo','niceo@example.com','Rap/Hip-Hop','Uptown Harlem'),

  -- Rock (7)
  ('CrimsonAshes','crimsonashes@example.com','Rock','Downtown Harlem'),
  ('TheIronHowl','ironhowl@example.com','Rock','Harlem'),
  ('MidnightStatic','midnightstatic@example.com','Rock','Uptown Harlem'),
  ('StoneReverie','stonereverie@example.com','Rock','Downtown Harlem'),
  ('SilverRiot','silverriot@example.com','Rock','Harlem'),
  ('NeonWidow','neonwidow@example.com','Rock','Uptown Harlem'),
  ('EchoesDivide','echoesdivide@example.com','Rock','Downtown Harlem'),

  -- Pop (6)
  ('Celecetricity','celecetricity@example.com','Pop','Harlem'),
  ('TiffanyLucky','tiffanylucky@example.com','Pop','Uptown Harlem'),
  ('BrokenCliff','brokencliff@example.com','Pop','Downtown Harlem'),
  ('AlmostSoon','almostsoon@example.com','Pop','Harlem'),
  ('IcedChocolate','icedchocolate@example.com','Pop','Uptown Harlem'),
  ('JimmyNuevo','jimmynuevo@example.com','Pop','Downtown Harlem')
),
ins_users AS (
  INSERT INTO users (user_id, username, email, password_hash, role, jurisdiction_id, created_at)
  SELECT uuid_generate_v4(), s.username, s.email, 'mvp_dev_hash', 'artist', j.jurisdiction_id, NOW()
  FROM seed s
  JOIN j ON j.jur_name = s.jur_name
  ON CONFLICT (email) DO NOTHING
  RETURNING user_id, username, email
)
INSERT INTO songs (song_id, artist_id, title, genre_id, file_url, created_at)
SELECT 
  uuid_generate_v4(),
  u.user_id,
  'Debut Single - ' || u.username,
  g.genre_id,
  'file://mvp/' || u.username || '/track1.mp3',
  NOW()
FROM ins_users u
JOIN seed s ON s.username = u.username
JOIN g ON g.genre_name = s.genre_name;

-- Insert 20 listeners; each supports an existing artist (by username).
INSERT INTO users (username, email, password_hash, role, supported_artist_id, jurisdiction_id, created_at)
SELECT
  s.username,
  s.email,
  'mvp_dev_hash' AS password_hash,   -- placeholder for MVP
  'listener'      AS role,
  a.user_id       AS supported_artist_id,
  j.jurisdiction_id,
  NOW()
FROM (
  VALUES
  -- 10 men
  ('JohnRiver',   'john.river@example.com',   'SnubCheezy'),
  ('MichaelStone','michael.stone@example.com','CoolC'),
  ('DavidHolt',   'david.holt@example.com',   'FirstClass'),
  ('ChrisKnight', 'chris.knight@example.com', 'TechChambers'),
  ('RobertAsh',   'robert.ash@example.com',   'KevHutch'),
  ('JamesVale',   'james.vale@example.com',   'Niceo'),
  ('BrianLake',   'brian.lake@example.com',   'CrimsonAshes'),
  ('KevinStorm',  'kevin.storm@example.com',  'MidnightStatic'),
  ('ThomasHale',  'thomas.hale@example.com',  'StoneReverie'),
  ('DanielCross', 'daniel.cross@example.com', 'SilverRiot'),

  -- 10 women
  ('SarahBloom',  'sarah.bloom@example.com',  'NeonWidow'),
  ('EmilySkye',   'emily.skye@example.com',   'EchoesDivide'),
  ('JessicaFrost','jessica.frost@example.com','Celecetricity'),
  ('AmandaRay',   'amanda.ray@example.com',   'TiffanyLucky'),
  ('OliviaMoon',  'olivia.moon@example.com',  'BrokenCliff'),
  ('HannahCole',  'hannah.cole@example.com',  'AlmostSoon'),
  ('LaurenKing',  'lauren.king@example.com',  'IcedChocolate'),
  ('MeganDrew',   'megan.drew@example.com',   'JimmyNuevo'),
  ('RachelFaye',  'rachel.faye@example.com',  'EchoesDivide'),
  ('SophiaLane',  'sophia.lane@example.com',  'SnubCheezy')
) AS s(username, email, supports)
JOIN users AS a
  ON a.username = s.supports AND a.role = 'artist'
CROSS JOIN (
  SELECT jurisdiction_id
  FROM jurisdictions
  WHERE name = 'Harlem'
  LIMIT 1
) AS j
ON CONFLICT (email) DO NOTHING;

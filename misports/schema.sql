PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS raw_captures (
  id INTEGER PRIMARY KEY,
  captured_at TEXT NOT NULL,
  dataset TEXT NOT NULL,
  path TEXT NOT NULL,
  record_count INTEGER,
  sha256 TEXT,
  UNIQUE(dataset, path)
);

CREATE TABLE IF NOT EXISTS step_minutes (
  minute_ts INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  date_local TEXT NOT NULL,
  steps INTEGER NOT NULL,
  distance_m REAL NOT NULL,
  calories_kcal REAL NOT NULL,
  device_id TEXT,
  source TEXT NOT NULL DEFAULT 'cloud',
  PRIMARY KEY (minute_ts, source)
);

CREATE TABLE IF NOT EXISTS calorie_minutes (
  minute_ts INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  date_local TEXT NOT NULL,
  calories_kcal REAL NOT NULL,
  device_id TEXT,
  source TEXT NOT NULL DEFAULT 'cloud',
  PRIMARY KEY (minute_ts, source)
);

CREATE TABLE IF NOT EXISTS heart_rate_samples (
  ts INTEGER NOT NULL,
  date_local TEXT NOT NULL,
  bpm INTEGER NOT NULL,
  sample_type TEXT NOT NULL,
  device_id TEXT,
  source TEXT NOT NULL DEFAULT 'cloud',
  PRIMARY KEY (ts, sample_type, source)
);

CREATE TABLE IF NOT EXISTS resting_heart_rate (
  date_local TEXT NOT NULL,
  ts INTEGER NOT NULL,
  bpm INTEGER NOT NULL,
  source TEXT NOT NULL DEFAULT 'cloud',
  PRIMARY KEY (date_local, source)
);

CREATE TABLE IF NOT EXISTS sleep_sessions (
  id INTEGER PRIMARY KEY,
  source_id TEXT NOT NULL UNIQUE,
  date_local TEXT NOT NULL,
  start_ts INTEGER NOT NULL,
  end_ts INTEGER NOT NULL,
  duration_min INTEGER NOT NULL,
  asleep_min INTEGER,
  awake_min INTEGER,
  awake_count INTEGER,
  avg_hr INTEGER,
  min_hr INTEGER,
  max_hr INTEGER,
  deep_min INTEGER,
  light_min INTEGER,
  rem_min INTEGER,
  score INTEGER,
  is_nap INTEGER NOT NULL DEFAULT 0,
  algorithm_version INTEGER,
  raw_ref TEXT
);

CREATE TABLE IF NOT EXISTS sleep_stages (
  session_id INTEGER NOT NULL REFERENCES sleep_sessions(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  start_ts INTEGER NOT NULL,
  end_ts INTEGER NOT NULL,
  stage TEXT NOT NULL,
  PRIMARY KEY (session_id, seq)
);

CREATE TABLE IF NOT EXISTS body_measurements (
  id INTEGER PRIMARY KEY,
  source_id TEXT NOT NULL UNIQUE,
  ts INTEGER NOT NULL,
  date_local TEXT NOT NULL,
  device_id TEXT,
  weight_kg REAL NOT NULL,
  bmi REAL,
  body_fat_pct REAL,
  muscle_mass_kg REAL,
  muscle_rate_pct REAL,
  skeletal_muscle_mass_kg REAL,
  body_moisture_mass_kg REAL,
  moisture_rate_pct REAL,
  bone_mass_kg REAL,
  protein_mass_kg REAL,
  protein_rate_pct REAL,
  fat_mass_kg REAL,
  fat_free_body_kg REAL,
  visceral_fat REAL,
  basal_metabolism_kcal INTEGER,
  body_age INTEGER,
  body_score INTEGER,
  whr REAL,
  standard_weight_kg REAL,
  extra_json TEXT,
  raw_ref TEXT
);

CREATE TABLE IF NOT EXISTS activities (
  id INTEGER PRIMARY KEY,
  source_id TEXT NOT NULL UNIQUE,
  sport_key TEXT NOT NULL,
  sport_category TEXT,
  sport_type_code INTEGER,
  start_ts INTEGER NOT NULL,
  end_ts INTEGER NOT NULL,
  date_local TEXT NOT NULL,
  duration_s INTEGER,
  distance_m REAL,
  calories_kcal REAL,
  steps INTEGER,
  avg_hr INTEGER,
  max_hr INTEGER,
  min_hr INTEGER,
  avg_cadence INTEGER,
  max_cadence INTEGER,
  avg_stride_cm INTEGER,
  avg_speed_mps REAL,
  max_speed_mps REAL,
  avg_pace_s_per_km REAL,
  max_pace_s_per_km REAL,
  min_pace_s_per_km REAL,
  hr_warm_up_s INTEGER,
  hr_fat_burning_s INTEGER,
  hr_aerobic_s INTEGER,
  hr_anaerobic_s INTEGER,
  hr_extreme_s INTEGER,
  avg_height_m REAL,
  max_height_m REAL,
  min_height_m REAL,
  vo2max INTEGER,
  train_effect REAL,
  anaerobic_train_effect REAL,
  training_load INTEGER,
  recovery_time INTEGER,
  avg_spo2 INTEGER,
  valid_duration_s INTEGER,
  stroke_count INTEGER,
  avg_stroke_frequency REAL,
  max_stroke_freq INTEGER,
  avg_swolf INTEGER,
  best_swolf INTEGER,
  turn_count INTEGER,
  pool_width REAL,
  main_posture INTEGER,
  total_calories_kcal REAL,
  avg_pace_s_per_100m REAL,
  max_pace_s_per_100m REAL,
  min_pace_s_per_100m REAL,
  extra_json TEXT,
  fds_sid TEXT,
  proto_type INTEGER,
  report_version INTEGER,
  report_time INTEGER,
  tz_in_15min INTEGER,
  fds_status TEXT,
  fds_collected INTEGER NOT NULL DEFAULT 0,
  fds_collected_at INTEGER,
  raw_ref TEXT
);

CREATE TABLE IF NOT EXISTS activity_samples (
  activity_id INTEGER NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
  offset_s INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  hr INTEGER,
  cadence INTEGER,
  distance_m REAL,
  calories_kcal REAL,
  stride_cm INTEGER,
  integer_km_flag INTEGER,
  speed_mps REAL,
  altitude_m REAL,
  PRIMARY KEY (activity_id, offset_s)
);

CREATE TABLE IF NOT EXISTS activity_track_points (
  activity_id INTEGER NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  ts INTEGER NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  accuracy_m REAL,
  speed_mps REAL,
  gps_source INTEGER,
  altitude_m REAL,
  hdop REAL,
  PRIMARY KEY (activity_id, seq)
);

CREATE TABLE IF NOT EXISTS sync_state (
  dataset TEXT NOT NULL,
  data_key TEXT NOT NULL,
  last_synced_at INTEGER,
  coverage_start INTEGER,
  coverage_end INTEGER,
  PRIMARY KEY (dataset, data_key)
);

CREATE INDEX IF NOT EXISTS idx_step_minutes_date ON step_minutes(date_local);
CREATE INDEX IF NOT EXISTS idx_calorie_minutes_date ON calorie_minutes(date_local);
CREATE INDEX IF NOT EXISTS idx_heart_rate_date ON heart_rate_samples(date_local);
CREATE INDEX IF NOT EXISTS idx_sleep_date ON sleep_sessions(date_local);
CREATE INDEX IF NOT EXISTS idx_body_date ON body_measurements(date_local);
CREATE INDEX IF NOT EXISTS idx_activities_start ON activities(start_ts);
CREATE INDEX IF NOT EXISTS idx_activities_type_date ON activities(sport_key, date_local);
CREATE INDEX IF NOT EXISTS idx_samples_activity_ts ON activity_samples(activity_id, ts);
CREATE INDEX IF NOT EXISTS idx_track_activity_ts ON activity_track_points(activity_id, ts);

CREATE VIEW IF NOT EXISTS v_daily_activity AS
SELECT
  s.date_local,
  SUM(s.steps) AS steps,
  ROUND(SUM(s.distance_m), 1) AS distance_m,
  ROUND(SUM(s.calories_kcal), 1) AS step_calories_kcal,
  ROUND((
    SELECT SUM(c.calories_kcal)
    FROM calorie_minutes c
    WHERE c.date_local = s.date_local
  ), 1) AS total_calories_kcal
FROM step_minutes s
GROUP BY s.date_local;

CREATE VIEW IF NOT EXISTS v_daily_sleep AS
SELECT
  date_local,
  COUNT(*) AS sessions,
  SUM(duration_min) AS duration_min,
  SUM(asleep_min) AS asleep_min,
  SUM(deep_min) AS deep_min,
  SUM(light_min) AS light_min,
  SUM(rem_min) AS rem_min,
  SUM(awake_min) AS awake_min,
  AVG(avg_hr) AS avg_hr,
  AVG(score) AS score
FROM sleep_sessions
GROUP BY date_local;

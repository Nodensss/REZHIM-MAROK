PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS grade (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, aliases TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS param (
 key TEXT PRIMARY KEY, name TEXT NOT NULL, tag TEXT NOT NULL DEFAULT '', unit TEXT NOT NULL DEFAULT '',
 precision INTEGER NOT NULL DEFAULT 2 CHECK(precision BETWEEN 0 AND 6),
 role TEXT NOT NULL CHECK(role IN ('measurement','setpoint','controller_output','suutp','lab')),
 load_dependency TEXT NOT NULL CHECK(load_dependency IN ('base','scales','independent')),
 group_name TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS source (
 id INTEGER PRIMARY KEY, name TEXT NOT NULL, sha256 TEXT NOT NULL UNIQUE, path TEXT NOT NULL,
 kind TEXT NOT NULL, screen TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS doc_version (
 id INTEGER PRIMARY KEY, grade_id INTEGER NOT NULL REFERENCES grade(id), version TEXT NOT NULL,
 valid_from TEXT NOT NULL, valid_to TEXT, source_id INTEGER NOT NULL REFERENCES source(id), notes TEXT NOT NULL DEFAULT '',
 UNIQUE(grade_id, version), CHECK(valid_to IS NULL OR valid_to >= valid_from)
);
CREATE TABLE IF NOT EXISTS norm (
 id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL REFERENCES doc_version(id), param_key TEXT NOT NULL REFERENCES param(key),
 lower_value REAL, target REAL, upper_value REAL, section TEXT NOT NULL, row_number TEXT NOT NULL,
 UNIQUE(doc_id,param_key), CHECK(lower_value IS NULL OR upper_value IS NULL OR lower_value <= upper_value)
);
CREATE TABLE IF NOT EXISTS recipe (
 id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL REFERENCES doc_version(id), zones TEXT NOT NULL,
 version TEXT NOT NULL, component TEXT NOT NULL, percent REAL, mass REAL, count INTEGER
);
CREATE TABLE IF NOT EXISTS observation (
 id INTEGER PRIMARY KEY, grade_id INTEGER NOT NULL REFERENCES grade(id), line TEXT NOT NULL CHECK(line IN ('A','B')),
 timestamp TEXT NOT NULL, a103 TEXT NOT NULL CHECK(a103 IN ('running','stopped','unknown')),
 ab124 TEXT NOT NULL CHECK(ab124 IN ('running','stopped','unknown')),
 suutp TEXT NOT NULL CHECK(suutp IN ('on','off','unknown')), steady INTEGER CHECK(steady IN (0,1)),
 doc_id INTEGER REFERENCES doc_version(id), recipe12 TEXT NOT NULL DEFAULT '', recipe3 TEXT NOT NULL DEFAULT '',
 UNIQUE(line,timestamp)
);
CREATE TABLE IF NOT EXISTS measurement (
 id INTEGER PRIMARY KEY, observation_id INTEGER NOT NULL REFERENCES observation(id), param_key TEXT NOT NULL REFERENCES param(key),
 number REAL, state TEXT NOT NULL CHECK(state IN ('number','missing','error')),
 status TEXT NOT NULL CHECK(status IN ('confirmed','review','rejected')),
 CHECK((state='number' AND number IS NOT NULL) OR (state IN ('missing','error') AND number IS NULL)),
 UNIQUE(observation_id,param_key,number)
);
CREATE TABLE IF NOT EXISTS value_source (
 value_id INTEGER NOT NULL REFERENCES measurement(id), source_id INTEGER NOT NULL REFERENCES source(id),
 PRIMARY KEY(value_id,source_id)
);
CREATE TABLE IF NOT EXISTS pump_map (
 observation_id INTEGER NOT NULL REFERENCES observation(id), zone INTEGER NOT NULL CHECK(zone BETWEEN 1 AND 3),
 pump INTEGER NOT NULL CHECK(pump BETWEEN 1 AND 4), PRIMARY KEY(observation_id,zone), UNIQUE(observation_id,pump)
);
CREATE TABLE IF NOT EXISTS transition (
 id INTEGER PRIMARY KEY, grade_from INTEGER REFERENCES grade(id), grade_to INTEGER REFERENCES grade(id),
 line TEXT NOT NULL CHECK(line IN ('A','B')), start TEXT NOT NULL, end TEXT,
 observation_from INTEGER REFERENCES observation(id), observation_to INTEGER REFERENCES observation(id)
);
CREATE TABLE IF NOT EXISTS audit (
 id INTEGER PRIMARY KEY, created_at TEXT NOT NULL, action TEXT NOT NULL, details TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
INSERT OR IGNORE INTO metadata VALUES ('package_version','0');
INSERT OR IGNORE INTO metadata VALUES ('schema_version','1');
CREATE TABLE IF NOT EXISTS doc_details (doc_id INTEGER PRIMARY KEY REFERENCES doc_version(id), payload TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS observation_details (observation_id INTEGER PRIMARY KEY REFERENCES observation(id), payload TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS migration_run (sha256 TEXT PRIMARY KEY, created_at TEXT NOT NULL, report TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS ai_batch (
 id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL UNIQUE REFERENCES source(id), created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS ai_observation (
 batch_id INTEGER NOT NULL REFERENCES ai_batch(id), observation_id INTEGER NOT NULL REFERENCES observation(id),
 photo_source_id INTEGER REFERENCES source(id), metadata_json TEXT NOT NULL, warnings_json TEXT NOT NULL,
 PRIMARY KEY(batch_id,observation_id)
);
CREATE TABLE IF NOT EXISTS ai_item (
 id INTEGER PRIMARY KEY, batch_id INTEGER NOT NULL REFERENCES ai_batch(id), observation_id INTEGER NOT NULL REFERENCES observation(id),
 position INTEGER NOT NULL, kind TEXT NOT NULL, key_hint TEXT NOT NULL DEFAULT '', param_key TEXT REFERENCES param(key),
 tag TEXT NOT NULL DEFAULT '', label TEXT NOT NULL DEFAULT '', unit TEXT NOT NULL DEFAULT '',
 raw_value TEXT NOT NULL, number REAL, parse_error TEXT NOT NULL DEFAULT '',
 status TEXT NOT NULL CHECK(status IN ('review','confirmed','rejected')), value_id INTEGER REFERENCES measurement(id),
 UNIQUE(batch_id,observation_id,position)
);

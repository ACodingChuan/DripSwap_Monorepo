-- dripswap 业务数据库 & 用户（首次初始化时创建）
DO
$$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dripswap') THEN
    CREATE ROLE dripswap LOGIN PASSWORD 'dripswap';
  END IF;
END
$$;

SELECT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'dripswap') AS db_exists;
\gset
\if :db_exists
\else
  CREATE DATABASE dripswap OWNER dripswap;
\endif

ALTER DATABASE dripswap OWNER TO dripswap;
GRANT ALL PRIVILEGES ON DATABASE dripswap TO dripswap;

\connect dripswap
ALTER SCHEMA public OWNER TO dripswap;
GRANT ALL ON SCHEMA public TO dripswap;

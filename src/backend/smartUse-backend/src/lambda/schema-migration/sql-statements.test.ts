import * as fs from 'fs';
import * as path from 'path';
import { splitSqlStatements } from './sql-statements';

test('splits multiple statements and trims whitespace', () => {
  const sql = `
    CREATE TABLE a (id INT);

    CREATE TABLE b (id INT);
  `;
  expect(splitSqlStatements(sql)).toEqual([
    'CREATE TABLE a (id INT)',
    'CREATE TABLE b (id INT)',
  ]);
});

test('drops empty statements caused by stray semicolons', () => {
  expect(splitSqlStatements('SELECT 1;;  ;')).toEqual(['SELECT 1']);
});

test('does not split on a semicolon inside a line comment', () => {
  const sql = `
    -- see the note below); more comment text
    CREATE TABLE a (id INT);
    CREATE TABLE b (id INT);
  `;
  expect(splitSqlStatements(sql)).toEqual([
    '-- see the note below); more comment text\n    CREATE TABLE a (id INT)',
    'CREATE TABLE b (id INT)',
  ]);
});

test('does not split on a semicolon inside a string literal', () => {
  const sql = `INSERT INTO t (v) VALUES ('a;b'); SELECT 1;`;
  expect(splitSqlStatements(sql)).toEqual([
    "INSERT INTO t (v) VALUES ('a;b')",
    'SELECT 1',
  ]);
});

const SQL_LEADING_KEYWORDS = [
  'CREATE', 'ALTER', 'DROP', 'INSERT', 'UPDATE', 'DELETE', 'SELECT', 'GRANT', 'REVOKE',
];

test('the real schema.sql parses into one statement per table, index, and view, with every statement starting on a real SQL keyword', () => {
  const sql = fs.readFileSync(path.join(__dirname, '../../db/schema.sql'), 'utf-8');
  const statements = splitSqlStatements(sql);
  expect(statements.length).toBeGreaterThanOrEqual(8);
  for (const statement of statements) {
    const code = statement
      .split('\n')
      .filter((line) => !line.trim().startsWith('--'))
      .join('\n')
      .trim();
    const leadingWord = code.split(/\s+/)[0]?.toUpperCase();
    expect(SQL_LEADING_KEYWORDS).toContain(leadingWord);
  }
});

test('schema.sql defines the deployment-group final_* tables idempotently', () => {
  const sql = fs.readFileSync(path.join(__dirname, '../../db/schema.sql'), 'utf-8');
  const statements = splitSqlStatements(sql);
  for (const pattern of [
    /CREATE TABLE IF NOT EXISTS final_sends/,
    /CREATE TABLE IF NOT EXISTS final_answers/,
    /CREATE TABLE IF NOT EXISTS final_metrics/,
    /ALTER TABLE users ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ/,
    /CREATE INDEX IF NOT EXISTS idx_final_metrics_user_id/,
  ]) {
    expect(statements.some((s) => pattern.test(s))).toBe(true);
  }
});

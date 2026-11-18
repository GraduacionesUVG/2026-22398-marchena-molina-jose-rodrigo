// A plain sql.split(';') breaks on a ';' inside a `--` line comment or a
// string literal, splicing the comment's tail onto the next real statement.
// This tracks just enough lexical state (line comments, single-quoted
// strings) to only split on statement-terminating semicolons.
export function splitSqlStatements(sql: string): string[] {
  const statements: string[] = [];
  let current = '';
  let inLineComment = false;
  let inString = false;

  for (let i = 0; i < sql.length; i++) {
    const char = sql[i];

    if (inLineComment) {
      current += char;
      if (char === '\n') {
        inLineComment = false;
      }
      continue;
    }

    if (inString) {
      current += char;
      if (char === "'") {
        inString = false;
      }
      continue;
    }

    if (char === '-' && sql[i + 1] === '-') {
      inLineComment = true;
      current += char;
      continue;
    }

    if (char === "'") {
      inString = true;
      current += char;
      continue;
    }

    if (char === ';') {
      statements.push(current);
      current = '';
      continue;
    }

    current += char;
  }
  statements.push(current);

  return statements.map((statement) => statement.trim()).filter((statement) => statement.length > 0);
}

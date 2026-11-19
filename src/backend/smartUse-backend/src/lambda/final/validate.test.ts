import { parseFinalSend, flattenAnswers, filterMetrics } from './validate';

function validBody(): any {
  return {
    end_reason: 'completed',
    forms: {
      usability: {
        version: 2,
        answers: { d1_1: 5, d1_2: 4, d2_3: 2, d8_3: 'texto libre del participante', d11_1: 0 },
      },
    },
    metrics: [
      { decision_point: 'screen_on', duration_ms: 412, battery_delta_pct: 0, current_mean: -310000, peak_rss_kb: 98304 },
      { decision_point: 'active_use', duration_ms: 35 },
    ],
  };
}

function withAnswer(value: unknown) {
  const body = validBody();
  body.forms.usability.answers.d1_1 = value;
  return body;
}

test('accepts the contract example', () => {
  const result = parseFinalSend(validBody());
  expect('value' in result && result.value.metrics.length).toBe(2);
});

test.each<[string, unknown]>([
  ['null body', null],
  ['array body', []],
  ['string body', 'x'],
  ['unknown end_reason', { ...validBody(), end_reason: 'done' }],
  ['missing end_reason', { ...validBody(), end_reason: undefined }],
  ['missing forms', { ...validBody(), forms: undefined }],
  ['forms is an array', { ...validBody(), forms: [] }],
  ['forms without usability', { ...validBody(), forms: {} }],
  ['version 0', { ...validBody(), forms: { usability: { version: 0, answers: {} } } }],
  ['version 1.5', { ...validBody(), forms: { usability: { version: 1.5, answers: {} } } }],
  ['version as string', { ...validBody(), forms: { usability: { version: '2', answers: {} } } }],
  ['answers is an array', { ...validBody(), forms: { usability: { version: 2, answers: [] } } }],
  ['boolean answer', withAnswer(true)],
  ['null answer', withAnswer(null)],
  ['array answer', withAnswer([1])],
  ['object answer', withAnswer({})],
  ['float answer', withAnswer(5.5)],
  ['answer above int32', withAnswer(2147483648)],
  ['metrics is an object', { ...validBody(), metrics: {} }],
])('rejects %s', (_name, body) => {
  expect('error' in parseFinalSend(body)).toBe(true);
});

test.each<[string, unknown]>([
  ['an answer of 5.0 (parses as 5)', JSON.parse('{"end_reason":"completed","forms":{"usability":{"version":2,"answers":{"d1_1":5.0}}}}')],
  ['an answer at the int32 minimum', withAnswer(-2147483648)],
  ['a 5000-character open answer', withAnswer('x'.repeat(5000))],
  ['an extra form', { ...validBody(), forms: { ...validBody().forms, other: { version: 1, answers: { q: 'x' } } } }],
])('accepts %s', (_name, body) => {
  expect('value' in parseFinalSend(body)).toBe(true);
});

test.each<[string, unknown]>([
  ['missing', undefined],
  ['null', null],
])('treats %s metrics as an empty array', (_name, metrics) => {
  const result = parseFinalSend({ ...validBody(), metrics });
  expect('value' in result && result.value.metrics).toEqual([]);
});

test('flattens answers into one row per question, int or text', () => {
  expect(flattenAnswers({ usability: { version: 2, answers: { d1_1: 5, d8_3: 'hi' } } })).toEqual([
    { form_id: 'usability', form_version: 2, question_id: 'd1_1', value_int: 5, value_text: null },
    { form_id: 'usability', form_version: 2, question_id: 'd8_3', value_int: null, value_text: 'hi' },
  ]);
});

test.each<[string, unknown]>([
  ['not an object', 'x'],
  ['unknown decision_point', { decision_point: 'other', duration_ms: 1 }],
  ['missing duration_ms', { decision_point: 'screen_on' }],
  ['null duration_ms', { decision_point: 'screen_on', duration_ms: null }],
  ['negative duration_ms', { decision_point: 'screen_on', duration_ms: -1 }],
  ['fractional duration_ms', { decision_point: 'screen_on', duration_ms: 1.5 }],
  ['duration_ms above int32', { decision_point: 'screen_on', duration_ms: 2147483648 }],
  ['fractional battery_delta_pct', { decision_point: 'screen_on', duration_ms: 1, battery_delta_pct: 1.5 }],
  ['string peak_rss_kb', { decision_point: 'screen_on', duration_ms: 1, peak_rss_kb: '9' }],
  ['unsafe current_mean', { decision_point: 'screen_on', duration_ms: 1, current_mean: 2 ** 53 }],
])('skips a metric record with %s', (_name, record) => {
  const result = filterMetrics([record]);
  expect(result.skipped).toBe(1);
  expect(result.valid).toHaveLength(0);
});

test('keeps a valid record, nulling absent/null optional fields and dropping unknown keys', () => {
  const result = filterMetrics([{ decision_point: 'active_use', duration_ms: 35, battery_delta_pct: null, extra: 1 }]);
  expect(result).toEqual({
    valid: [{ decision_point: 'active_use', duration_ms: 35, battery_delta_pct: null, current_mean: null, peak_rss_kb: null }],
    skipped: 0,
    reasons: {},
  });
});

test('keeps a negative current_mean (discharging)', () => {
  expect(filterMetrics([{ decision_point: 'screen_on', duration_ms: 1, current_mean: -310000 }]).valid[0].current_mean).toBe(-310000);
});

test('keeps 10 000 valid records', () => {
  const records = Array.from({ length: 10000 }, (_, i) => ({ decision_point: 'screen_on', duration_ms: i }));
  const result = filterMetrics(records);
  expect(result.valid).toHaveLength(10000);
  expect(result.skipped).toBe(0);
});

test('counts skip reasons by the field that failed', () => {
  const result = filterMetrics([
    'x',
    { decision_point: 'other', duration_ms: 1 },
    { decision_point: 'screen_on' },
    { decision_point: 'screen_on', duration_ms: -1 },
  ]);
  expect(result.reasons).toEqual({ not_object: 1, decision_point: 1, duration_ms: 2 });
});

test('rejects a form version above int32 (would overflow form_version and 5xx forever)', () => {
  expect('error' in parseFinalSend({ ...validBody(), forms: { usability: { version: 3000000000, answers: {} } } })).toBe(true);
});

// Postgres jsonb rejects \u0000 and lone surrogates; left as-is they'd fail
// the store with a 5xx the app retries forever, so they're replaced, not rejected.
test.each<[string, string, string]>([
  ['a NUL character', 'a\u0000b', 'a�b'],
  ['a lone high surrogate (emoji cut in half)', 'ok \ud83d', 'ok �'],
  ['a lone low surrogate', '\ude00x', '�x'],
  ['a complete emoji (left alone)', 'hola 😀', 'hola 😀'],
])('cleans %s in open-text answers', (_name, raw, cleaned) => {
  const result = parseFinalSend(withAnswer(raw));
  expect('value' in result && result.value.forms.usability.answers.d1_1).toBe(cleaned);
});

test('cleans NUL characters in form and question ids', () => {
  const body = validBody();
  body.forms = { usability: { version: 2, answers: { 'q\u0000': 'x' } }, ['f\u0000']: { version: 1, answers: { a: 1 } } };
  const result = parseFinalSend(body);
  expect('value' in result && Object.keys(result.value.forms)).toEqual(['usability', 'f�']);
  expect('value' in result && Object.keys(result.value.forms.usability.answers)).toEqual(['q�']);
});

module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  transform: {
    '^.+\\.tsx?$': 'ts-jest',
    '\\.sql$': '<rootDir>/jest.sql-transformer.js',
  },
  testPathIgnorePatterns: ['/node_modules/', '/cdk.out/'],
};

import { Test, TestingModule } from '@nestjs/testing';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { App } from 'supertest/types.js';
import { AppModule } from './../src/app.module.js';

describe('AppController (e2e)', () => {
  let app: INestApplication<App>;

  beforeEach(async () => {
    const moduleFixture: TestingModule = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();

    app = moduleFixture.createNestApplication();
    await app.init();
  });

  it('/ (GET)', () => {
    return request(app.getHttpServer())
      .get('/')
      .expect(200)
      .expect('Hello World!');
  });

  it('/health/database (GET) reports the database it can query', async () => {
    const expectedDatabase = new URL(process.env.DATABASE_URL as string).pathname.slice(1);

    const response = await request(app.getHttpServer())
      .get('/health/database')
      .expect(200);

    expect(response.body).toEqual({
      status: 'ok',
      provider: 'postgresql',
      database: expectedDatabase,
    });
  });

  afterEach(async () => {
    await app.close();
  });
});

BEGIN;

ALTER TABLE "enrollment_challenges" ADD COLUMN "classCode" VARCHAR(64);

ALTER TABLE "students" ADD COLUMN "major" VARCHAR(64);
ALTER TABLE "students" ADD COLUMN "classCode" VARCHAR(64);

UPDATE "students"
SET "major" = CASE UPPER(SUBSTRING("studentId" FROM 1 FOR 2))
    WHEN 'CT' THEN 'Công nghệ thông tin'
    WHEN 'AT' THEN 'An toàn thông tin'
    WHEN 'DT' THEN 'Điện tử viễn thông'
    ELSE NULL
END
WHERE UPPER(SUBSTRING("studentId" FROM 1 FOR 2)) IN ('CT', 'AT', 'DT');

COMMIT;

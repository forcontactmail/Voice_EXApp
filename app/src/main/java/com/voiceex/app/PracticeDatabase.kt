package com.voiceex.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import kotlin.math.roundToInt

data class PracticeSessionHistory(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationSeconds: Long,
    val correctCount: Int,
    val firstTryCount: Int,
    val retryCount: Int,
) {
    val firstTryPercentage: Int
        get() = if (correctCount == 0) 0 else (firstTryCount * 100f / correctCount).roundToInt()
}

data class SessionQuestionDetail(
    val id: Long,
    val text: String,
    val cefr: String,
    val tipsCount: Int,
)

class PracticeDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE QUESTIONS (
                ID INTEGER PRIMARY KEY AUTOINCREMENT,
                QTEXT TEXT NOT NULL,
                TRANSLATION TEXT NOT NULL,
                ACCEPTED_TRANSLATIONS TEXT NOT NULL,
                CEFR TEXT NOT NULL CHECK (CEFR IN ('A1', 'A2', 'B1', 'B2', 'C1', 'C2')),
                WORD BOOLEAN NOT NULL DEFAULT 0 CHECK (WORD IN (0, 1)),
                ACTIVE INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE SESSIONS (
                ID INTEGER PRIMARY KEY AUTOINCREMENT,
                STARTED_AT INTEGER NOT NULL,
                ENDED_AT INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE SESSION_QUESTIONS (
                ID INTEGER PRIMARY KEY AUTOINCREMENT,
                SESSION_ID INTEGER NOT NULL REFERENCES SESSIONS(ID) ON DELETE CASCADE,
                QUESTION_ID INTEGER NOT NULL REFERENCES QUESTIONS(ID),
                TIPS_COUNT INTEGER NOT NULL DEFAULT 0,
                ANSWERED_CORRECTLY INTEGER NOT NULL DEFAULT 0,
                FIRST_TRY INTEGER NOT NULL DEFAULT 0,
                SKIPPED INTEGER NOT NULL DEFAULT 0,
                UNIQUE (SESSION_ID, QUESTION_ID)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX INDEX_SESSION_QUESTIONS_SESSION ON SESSION_QUESTIONS(SESSION_ID)")
        db.execSQL("CREATE INDEX INDEX_QUESTIONS_ACTIVE ON QUESTIONS(ACTIVE, ID)")
        db.execSQL("CREATE INDEX INDEX_QUESTIONS_TEXT ON QUESTIONS(QTEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val columns = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info(SESSION_QUESTIONS)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
            }
            if ("TIPS_CONT" in columns && "TIPS_COUNT" !in columns) {
                db.execSQL("ALTER TABLE SESSION_QUESTIONS ADD COLUMN TIPS_COUNT INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE SESSION_QUESTIONS SET TIPS_COUNT = TIPS_CONT")
            }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE QUESTIONS ADD COLUMN WORD BOOLEAN NOT NULL DEFAULT 0 CHECK (WORD IN (0, 1))")
        }
    }

    fun loadActiveQuestions(isWord: Boolean = false): List<Phrase> {
        val questions = mutableListOf<Phrase>()
        readableDatabase.query(
            "QUESTIONS",
            arrayOf("ID", "QTEXT", "TRANSLATION", "ACCEPTED_TRANSLATIONS", "CEFR", "WORD"),
            "ACTIVE = 1 AND WORD = ?",
            arrayOf(if (isWord) "1" else "0"),
            null,
            null,
            "ID",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                questions += Phrase(
                    prompt = cursor.getString(1),
                    translation = cursor.getString(2),
                    acceptedTranslations = decodeTranslations(cursor.getString(3)),
                    questionId = cursor.getLong(0),
                    cefr = cursor.getString(4),
                    isWord = cursor.getInt(5) != 0,
                )
            }
        }
        return questions
    }

    fun replaceActiveQuestions(phrases: List<Phrase>): List<Phrase> {
        val db = writableDatabase
        val result = mutableListOf<Phrase>()
        db.beginTransaction()
        try {
            val wordValue = if (phrases.firstOrNull()?.isWord == true) 1 else 0
            db.execSQL("UPDATE QUESTIONS SET ACTIVE = 0 WHERE WORD = ?", arrayOf(wordValue))
            for (phrase in phrases) {
                val existing = findQuestion(db, phrase.prompt, phrase.isWord)
                val cefr = if (phrase.isWord) phrase.cefr else existing?.cefr ?: phrase.cefr
                val values = ContentValues().apply {
                    put("QTEXT", phrase.prompt)
                    put("TRANSLATION", phrase.translation)
                    put("ACCEPTED_TRANSLATIONS", encodeTranslations(phrase.acceptedTranslations))
                    put("CEFR", cefr)
                    put("WORD", if (phrase.isWord) 1 else 0)
                    put("ACTIVE", 1)
                }
                val question = if (existing == null) {
                    val id = db.insertOrThrow("QUESTIONS", null, values)
                    phrase.copy(questionId = id, cefr = cefr)
                } else {
                    db.update("QUESTIONS", values, "ID = ?", arrayOf(existing.questionId.toString()))
                    phrase.copy(questionId = existing.questionId, cefr = cefr)
                }
                result += question
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return result
    }

    fun updateQuestionCefr(questionId: Long, cefr: String): Boolean {
        if (cefr !in CefrEstimator.levels) return false
        val values = ContentValues().apply { put("CEFR", cefr) }
        return writableDatabase.update("QUESTIONS", values, "ID = ?", arrayOf(questionId.toString())) == 1
    }

    fun startSession(startedAt: Long = System.currentTimeMillis()): Long {
        val values = ContentValues().apply { put("STARTED_AT", startedAt) }
        return writableDatabase.insertOrThrow("SESSIONS", null, values)
    }

    fun finishSession(sessionId: Long, endedAt: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply { put("ENDED_AT", endedAt) }
        writableDatabase.update("SESSIONS", values, "ID = ? AND ENDED_AT IS NULL", arrayOf(sessionId.toString()))
    }

    fun finishOpenSessions(endedAt: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply { put("ENDED_AT", endedAt) }
        writableDatabase.update("SESSIONS", values, "ENDED_AT IS NULL", null)
    }

    fun recordWrongAnswer(sessionId: Long, questionId: Long) {
        writableDatabase.execSQL(
            """
            INSERT INTO SESSION_QUESTIONS (SESSION_ID, QUESTION_ID, TIPS_COUNT)
            VALUES (?, ?, 1)
            ON CONFLICT(SESSION_ID, QUESTION_ID) DO UPDATE SET
                TIPS_COUNT = TIPS_COUNT + 1,
                SKIPPED = 0
            WHERE ANSWERED_CORRECTLY = 0
            """.trimIndent(),
            arrayOf(sessionId, questionId),
        )
    }

    fun recordCorrectAnswer(sessionId: Long, questionId: Long) {
        writableDatabase.execSQL(
            """
            INSERT INTO SESSION_QUESTIONS (
                SESSION_ID, QUESTION_ID, ANSWERED_CORRECTLY, FIRST_TRY
            ) VALUES (?, ?, 1, 1)
            ON CONFLICT(SESSION_ID, QUESTION_ID) DO UPDATE SET
                ANSWERED_CORRECTLY = 1,
                FIRST_TRY = CASE WHEN TIPS_COUNT = 0 THEN 1 ELSE 0 END,
                SKIPPED = 0
            WHERE ANSWERED_CORRECTLY = 0
            """.trimIndent(),
            arrayOf(sessionId, questionId),
        )
    }

    fun recordSkippedQuestion(sessionId: Long, questionId: Long) {
        writableDatabase.execSQL(
            """
            INSERT INTO SESSION_QUESTIONS (SESSION_ID, QUESTION_ID, SKIPPED)
            VALUES (?, ?, 1)
            ON CONFLICT(SESSION_ID, QUESTION_ID) DO UPDATE SET SKIPPED = 1
            WHERE ANSWERED_CORRECTLY = 0
            """.trimIndent(),
            arrayOf(sessionId, questionId),
        )
    }

    fun loadHistory(): List<PracticeSessionHistory> {
        val sessions = mutableListOf<PracticeSessionHistory>()
        readableDatabase.rawQuery(
            """
            SELECT
                S.ID,
                S.STARTED_AT,
                S.ENDED_AT,
                MAX(0, (S.ENDED_AT - S.STARTED_AT) / 1000),
                SUM(CASE WHEN SQ.ANSWERED_CORRECTLY = 1 THEN 1 ELSE 0 END),
                SUM(CASE WHEN SQ.ANSWERED_CORRECTLY = 1 AND SQ.FIRST_TRY = 1 THEN 1 ELSE 0 END),
                SUM(CASE WHEN SQ.ANSWERED_CORRECTLY = 1 AND SQ.FIRST_TRY = 0 THEN 1 ELSE 0 END)
            FROM SESSIONS S
            LEFT JOIN SESSION_QUESTIONS SQ ON SQ.SESSION_ID = S.ID
            WHERE S.ENDED_AT IS NOT NULL
            GROUP BY S.ID
            ORDER BY S.STARTED_AT DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sessions += PracticeSessionHistory(
                    id = cursor.getLong(0),
                    startedAt = cursor.getLong(1),
                    endedAt = cursor.getLong(2),
                    durationSeconds = cursor.getLong(3),
                    correctCount = cursor.getInt(4),
                    firstTryCount = cursor.getInt(5),
                    retryCount = cursor.getInt(6),
                )
            }
        }
        return sessions
    }

    fun loadCorrectQuestionDetails(sessionId: Long, firstTry: Boolean): List<SessionQuestionDetail> {
        val details = mutableListOf<SessionQuestionDetail>()
        readableDatabase.rawQuery(
            """
            SELECT Q.ID, Q.QTEXT, Q.CEFR, SQ.TIPS_COUNT
            FROM SESSION_QUESTIONS SQ
            JOIN QUESTIONS Q ON Q.ID = SQ.QUESTION_ID
            WHERE SQ.SESSION_ID = ?
                AND SQ.ANSWERED_CORRECTLY = 1
                AND SQ.FIRST_TRY = ?
            ORDER BY Q.ID
            """.trimIndent(),
            arrayOf(sessionId.toString(), if (firstTry) "1" else "0"),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                details += SessionQuestionDetail(
                    id = cursor.getLong(0),
                    text = cursor.getString(1),
                    cefr = cursor.getString(2),
                    tipsCount = cursor.getInt(3),
                )
            }
        }
        return details
    }

    private fun findQuestion(db: SQLiteDatabase, text: String, isWord: Boolean): ExistingQuestion? =
        db.query(
            "QUESTIONS",
            arrayOf("ID", "CEFR"),
            "QTEXT = ? AND WORD = ?",
            arrayOf(text, if (isWord) "1" else "0"),
            null,
            null,
            "ID",
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                ExistingQuestion(cursor.getLong(0), cursor.getString(1))
            } else {
                null
            }
        }

    private fun encodeTranslations(translations: List<String>): String =
        JSONArray(translations).toString()

    private fun decodeTranslations(value: String): List<String> {
        val json = JSONArray(value)
        return List(json.length()) { index -> json.getString(index) }
    }

    private data class ExistingQuestion(val questionId: Long, val cefr: String)

    companion object {
        private const val DATABASE_NAME = "voice_practice.db"
        private const val DATABASE_VERSION = 3
    }
}

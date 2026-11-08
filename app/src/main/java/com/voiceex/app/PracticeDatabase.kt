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

data class HierarchyNode(
    val id: Long? = null,
    val parentId: Long? = null,
    val name: String,
    val assetPath: String = name,
    val isFile: Boolean = false,
    val children: List<HierarchyNode> = emptyList(),
    val selected: Boolean = false,
    val indeterminate: Boolean = false,
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
            CREATE TABLE HIERARHY (
                ID INTEGER PRIMARY KEY AUTOINCREMENT,
                PARENT_ID INTEGER REFERENCES HIERARHY(ID) ON DELETE CASCADE,
                NAME TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE QUESTIONS (
                ID INTEGER PRIMARY KEY AUTOINCREMENT,
                QTEXT TEXT NOT NULL,
                TRANSLATION TEXT NOT NULL,
                ACCEPTED_TRANSLATIONS TEXT NOT NULL,
                CEFR TEXT NOT NULL CHECK (CEFR IN ('A1', 'A2', 'B1', 'B2', 'C1', 'C2')),
                IS_WORD BOOLEAN NOT NULL DEFAULT 0 CHECK (IS_WORD IN (0, 1)),
                HIERARHY_ID INTEGER REFERENCES HIERARHY(ID),
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
        if (oldVersion < 4) {
            migrateWordColumn(db)
        }
        if (oldVersion < 5) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS HIERARHY (
                    ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    PARENT_ID INTEGER REFERENCES HIERARHY(ID) ON DELETE CASCADE,
                    NAME TEXT NOT NULL
                )
                """.trimIndent(),
            )
            val hasHierarchyId = db.rawQuery("PRAGMA table_info(QUESTIONS)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "HIERARHY_ID") return@use true
                }
                false
            }
            if (!hasHierarchyId) {
                db.execSQL("ALTER TABLE QUESTIONS ADD COLUMN HIERARHY_ID INTEGER REFERENCES HIERARHY(ID)")
            }
        }
    }

    fun loadActiveQuestions(isWord: Boolean = false): List<Phrase> {
        val questions = mutableListOf<Phrase>()
        readableDatabase.query(
            "QUESTIONS",
            arrayOf("ID", "QTEXT", "TRANSLATION", "ACCEPTED_TRANSLATIONS", "CEFR", "IS_WORD"),
            "ACTIVE = 1 AND IS_WORD = ?",
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
            db.execSQL("UPDATE QUESTIONS SET ACTIVE = 0 WHERE IS_WORD = ?", arrayOf(wordValue))
            for (phrase in phrases) {
                val existing = findQuestion(db, phrase.prompt, phrase.isWord)
                val cefr = if (phrase.isWord) phrase.cefr else existing?.cefr ?: phrase.cefr
                val values = ContentValues().apply {
                    put("QTEXT", phrase.prompt)
                    put("TRANSLATION", phrase.translation)
                    put("ACCEPTED_TRANSLATIONS", encodeTranslations(phrase.acceptedTranslations))
                    put("CEFR", cefr)
                    put("IS_WORD", if (phrase.isWord) 1 else 0)
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

    fun addQuestion(prompt: String, translation: String, cefr: String, isWord: Boolean, hierarchyId: Long? = null): Long {
        require(prompt.isNotBlank()) { "Question text cannot be empty." }
        require(translation.isNotBlank()) { "Translation cannot be empty." }
        require(cefr in CefrEstimator.levels) { "Invalid CEFR level." }
        val values = ContentValues().apply {
            put("QTEXT", prompt.trim())
            put("TRANSLATION", translation.trim())
            put("ACCEPTED_TRANSLATIONS", encodeTranslations(listOf(translation.trim())))
            put("CEFR", cefr)
            put("IS_WORD", if (isWord) 1 else 0)
            put("HIERARHY_ID", hierarchyId)
            put("ACTIVE", 1)
        }
        return writableDatabase.insertOrThrow("QUESTIONS", null, values)
    }

    fun updateQuestion(questionId: Long, prompt: String, translation: String, cefr: String, hierarchyId: Long? = null): Boolean {
        require(prompt.isNotBlank()) { "Question text cannot be empty." }
        require(translation.isNotBlank()) { "Translation cannot be empty." }
        require(cefr in CefrEstimator.levels) { "Invalid CEFR level." }
        val db = writableDatabase
        val acceptedTranslations = db.query(
            "QUESTIONS",
            arrayOf("TRANSLATION", "ACCEPTED_TRANSLATIONS"),
            "ID = ?",
            arrayOf(questionId.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return false
            val previousTranslation = cursor.getString(0)
            val existingTranslations = decodeTranslations(cursor.getString(1))
            if (previousTranslation == translation.trim()) {
                existingTranslations
            } else {
                listOf(translation.trim()) + existingTranslations.drop(1)
            }
        }
        val values = ContentValues().apply {
            put("QTEXT", prompt.trim())
            put("TRANSLATION", translation.trim())
            put("ACCEPTED_TRANSLATIONS", encodeTranslations(acceptedTranslations))
            put("CEFR", cefr)
            put("HIERARHY_ID", hierarchyId)
        }
        return db.update("QUESTIONS", values, "ID = ?", arrayOf(questionId.toString())) == 1
    }

    fun syncHierarchyTree(nodes: List<HierarchyNode>): List<HierarchyNode> {
        val db = writableDatabase
        fun syncNode(node: HierarchyNode, parentId: Long?): HierarchyNode {
            val selection = if (parentId == null) {
                "PARENT_ID IS NULL AND NAME = ?"
            } else {
                "PARENT_ID = ? AND NAME = ?"
            }
            val args = if (parentId == null) {
                arrayOf(node.name)
            } else {
                arrayOf(parentId.toString(), node.name)
            }
            val existingId = db.query(
                "HIERARHY",
                arrayOf("ID"),
                selection,
                args,
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
            val id = existingId ?: db.insertOrThrow(
                "HIERARHY",
                null,
                ContentValues().apply {
                    put("PARENT_ID", parentId)
                    put("NAME", node.name)
                },
            )
            return node.copy(
                id = id,
                parentId = parentId,
                children = node.children.map { syncNode(it, id) },
            )
        }

        db.beginTransaction()
        try {
            db.execSQL("UPDATE QUESTIONS SET ACTIVE = 0 WHERE HIERARHY_ID IS NULL")
            val synced = nodes.map { syncNode(it, null) }
            val retainedIds = mutableSetOf<Long>()
            fun collectIds(items: List<HierarchyNode>) {
                items.forEach { node ->
                    node.id?.let(retainedIds::add)
                    collectIds(node.children)
                }
            }
            collectIds(synced)
            val staleIds = mutableListOf<Long>()
            db.query("HIERARHY", arrayOf("ID"), null, null, null, null, "ID DESC").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (id !in retainedIds) staleIds += id
                }
            }
            staleIds.forEach { id ->
                db.execSQL(
                    "UPDATE QUESTIONS SET ACTIVE = 0, HIERARHY_ID = NULL WHERE HIERARHY_ID = ?",
                    arrayOf(id),
                )
                db.delete("HIERARHY", "ID = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
            return synced
        } finally {
            db.endTransaction()
        }
    }

    fun loadQuestionsForHierarchy(hierarchyId: Long): List<Phrase> {
        val questions = mutableListOf<Phrase>()
        readableDatabase.query(
            "QUESTIONS",
            arrayOf("ID", "QTEXT", "TRANSLATION", "ACCEPTED_TRANSLATIONS", "CEFR", "IS_WORD"),
            "ACTIVE = 1 AND HIERARHY_ID = ?",
            arrayOf(hierarchyId.toString()),
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

    fun replaceQuestionsForHierarchy(hierarchyId: Long, questions: List<Phrase>): List<Phrase> {
        val db = writableDatabase
        val result = mutableListOf<Phrase>()
        db.beginTransaction()
        try {
            db.execSQL("UPDATE QUESTIONS SET ACTIVE = 0 WHERE HIERARHY_ID = ?", arrayOf(hierarchyId))
            questions.forEach { phrase ->
                val existing = db.query(
                    "QUESTIONS",
                    arrayOf("ID"),
                    "QTEXT = ? AND IS_WORD = ? AND HIERARHY_ID = ?",
                    arrayOf(phrase.prompt, if (phrase.isWord) "1" else "0", hierarchyId.toString()),
                    null,
                    null,
                    "ID",
                    "1",
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else null
                }
                val values = ContentValues().apply {
                    put("QTEXT", phrase.prompt)
                    put("TRANSLATION", phrase.translation)
                    put("ACCEPTED_TRANSLATIONS", encodeTranslations(phrase.acceptedTranslations))
                    put("CEFR", phrase.cefr)
                    put("IS_WORD", if (phrase.isWord) 1 else 0)
                    put("HIERARHY_ID", hierarchyId)
                    put("ACTIVE", 1)
                }
                val questionId = if (existing == null) {
                    db.insertOrThrow("QUESTIONS", null, values)
                } else {
                    db.update("QUESTIONS", values, "ID = ?", arrayOf(existing.toString()))
                    existing
                }
                result += phrase.copy(questionId = questionId)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return result
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
            "QTEXT = ? AND IS_WORD = ?",
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

    private fun migrateWordColumn(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE QUESTIONS_NEW (
                ID INTEGER PRIMARY KEY AUTOINCREMENT,
                QTEXT TEXT NOT NULL,
                TRANSLATION TEXT NOT NULL,
                ACCEPTED_TRANSLATIONS TEXT NOT NULL,
                CEFR TEXT NOT NULL CHECK (CEFR IN ('A1', 'A2', 'B1', 'B2', 'C1', 'C2')),
                IS_WORD BOOLEAN NOT NULL DEFAULT 0 CHECK (IS_WORD IN (0, 1)),
                ACTIVE INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO QUESTIONS_NEW (ID, QTEXT, TRANSLATION, ACCEPTED_TRANSLATIONS, CEFR, IS_WORD, ACTIVE)
            SELECT ID, QTEXT, TRANSLATION, ACCEPTED_TRANSLATIONS, CEFR, WORD, ACTIVE
            FROM QUESTIONS
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE SESSION_QUESTIONS_NEW (
                ID INTEGER PRIMARY KEY AUTOINCREMENT,
                SESSION_ID INTEGER NOT NULL REFERENCES SESSIONS(ID) ON DELETE CASCADE,
                QUESTION_ID INTEGER NOT NULL REFERENCES QUESTIONS_NEW(ID),
                TIPS_COUNT INTEGER NOT NULL DEFAULT 0,
                ANSWERED_CORRECTLY INTEGER NOT NULL DEFAULT 0,
                FIRST_TRY INTEGER NOT NULL DEFAULT 0,
                SKIPPED INTEGER NOT NULL DEFAULT 0,
                UNIQUE (SESSION_ID, QUESTION_ID)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO SESSION_QUESTIONS_NEW (
                ID, SESSION_ID, QUESTION_ID, TIPS_COUNT, ANSWERED_CORRECTLY, FIRST_TRY, SKIPPED
            )
            SELECT ID, SESSION_ID, QUESTION_ID, TIPS_COUNT, ANSWERED_CORRECTLY, FIRST_TRY, SKIPPED
            FROM SESSION_QUESTIONS
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE SESSION_QUESTIONS")
        db.execSQL("DROP TABLE QUESTIONS")
        db.execSQL("ALTER TABLE QUESTIONS_NEW RENAME TO QUESTIONS")
        db.execSQL("ALTER TABLE SESSION_QUESTIONS_NEW RENAME TO SESSION_QUESTIONS")
        db.execSQL("CREATE INDEX INDEX_SESSION_QUESTIONS_SESSION ON SESSION_QUESTIONS(SESSION_ID)")
        db.execSQL("CREATE INDEX INDEX_QUESTIONS_ACTIVE ON QUESTIONS(ACTIVE, ID)")
        db.execSQL("CREATE INDEX INDEX_QUESTIONS_TEXT ON QUESTIONS(QTEXT)")
    }

    companion object {
        private const val DATABASE_NAME = "voice_practice.db"
        private const val DATABASE_VERSION = 5
    }
}

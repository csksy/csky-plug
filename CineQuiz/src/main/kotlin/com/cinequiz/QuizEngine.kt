package com.cinequiz

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Owns the game flow for solo play and for the host of a room. The state is
// always rendered through onState so the WebView UI and the relay guests see
// exactly the same thing.
class QuizEngine(
    private val onState: (JSONObject) -> Unit,
    private val onSend: (JSONObject) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val ANSWER_MS = 12000L
        const val REVEAL_HOLD_MS = 3500L
    }

    enum class Phase { LOBBY, QUESTION, REVEAL, END }

    private val handler = Handler(Looper.getMainLooper())
    private var questions: List<QuizQuestion> = emptyList()
    private var round = -1
    private var phase = Phase.LOBBY
    private var deadline = 0L
    private val scores = LinkedHashMap<String, Int>()
    private val picked = LinkedHashMap<String, Int>()
    private var expectedPlayers = 1
    private var myName = "Player"
    private var answeredCount = 0

    fun configure(playerNames: List<String>, totalRounds: Int, myName: String) {
        this.myName = myName
        expectedPlayers = playerNames.size.coerceAtLeast(1)
        scores.clear()
        playerNames.forEach { scores[it] = 0 }
        questions = QuizQuestions.pick(totalRounds)
        round = -1
    }

    fun start() {
        if (questions.isEmpty()) return
        onSend(JSONObject().put("type", "QUIZ_START").put("total", questions.size))
        nextQuestion()
    }

    fun nextQuestion() {
        round++
        if (round >= questions.size) {
            return endGame()
        }
        val question = questions[round]
        phase = Phase.QUESTION
        picked.clear()
        answeredCount = 0
        deadline = now() + ANSWER_MS

        val state = JSONObject()
            .put("phase", "question")
            .put("round", round + 1)
            .put("total", questions.size)
            .put("category", question.category)
            .put("question", question.question)
            .put("options", JSONArray(question.options))
            .put("deadline", deadline)
            .put("scores", scoresJson())
        onState(state)
        onSend(state.put("type", "QUIZ_QUESTION"))

        handler.postDelayed({
            if (phase == Phase.QUESTION) reveal()
        }, ANSWER_MS + 200)
    }

    // a local answer from the player holding this device
    fun onLocalAnswer(choice: Int) {
        if (phase != Phase.QUESTION) return
        recordAnswer(myName, choice)
    }

    fun onRemoteAnswer(name: String, roundIdx: Int, choice: Int) {
        if (phase != Phase.QUESTION || roundIdx != round) return
        if (picked.containsKey(name)) return
        recordAnswer(name, choice)
    }

    private fun recordAnswer(name: String, choice: Int) {
        if (picked.containsKey(name)) return
        picked[name] = choice
        answeredCount++

        val ack = JSONObject()
            .put("type", "QUIZ_ANSWERED")
            .put("name", name)
        onState(ack)

        if (answeredCount >= expectedPlayers) {
            handler.removeCallbacksAndMessages(null)
            reveal()
        }
    }

    private fun reveal() {
        val question = questions.getOrNull(round) ?: return
        phase = Phase.REVEAL

        val remaining = (deadline - now()).coerceAtLeast(0)
        for ((name, choice) in picked) {
            if (choice == question.correct) {
                val bonus = (remaining * 5 / ANSWER_MS).toInt()
                scores[name] = (scores[name] ?: 0) + 10 + bonus
            }
        }

        val state = JSONObject()
            .put("phase", "reveal")
            .put("round", round + 1)
            .put("total", questions.size)
            .put("correct", question.correct)
            .put("picked", JSONObject(picked))
            .put("scores", scoresJson())
        onState(state)
        onSend(state.put("type", "QUIZ_REVEAL"))

        handler.postDelayed({ nextQuestion() }, REVEAL_HOLD_MS)
    }

    private fun endGame() {
        phase = Phase.END
        val state = JSONObject()
            .put("phase", "end")
            .put("scores", scoresJson())
        onState(state)
        onSend(JSONObject().put("type", "QUIZ_END").put("scores", scoresJson()))
    }

    private fun scoresJson(): JSONObject {
        val obj = JSONObject()
        for ((name, score) in scores) obj.put(name, score)
        return obj
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }
}

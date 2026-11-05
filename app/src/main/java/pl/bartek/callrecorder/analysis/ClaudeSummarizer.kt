package pl.bartek.callrecorder.analysis

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.json.JSONObject
import java.time.Duration

class SummaryException(message: String, val retryable: Boolean) : Exception(message)

/** Analiza transkrypcji przez Claude: temat, podsumowanie, ustalenia, zadania itd. (wynik w JSON). */
class ClaudeSummarizer(private val apiKey: String, private val model: String) {

    fun summarize(utterances: List<Utterance>, meta: CallMeta): JSONObject {
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofMinutes(5))
            .build()

        val transcript = utterances.joinToString("\n") { u ->
            val s = u.startMs / 1000
            "[%d:%02d] Mówca %s: %s".format(s / 60, s % 60, u.speaker, u.text)
        }
        val user = buildString {
            appendLine("Metadane rozmowy:")
            appendLine("- data: ${meta.date}")
            appendLine("- kierunek: ${meta.direction}")
            appendLine("- rozmówca (z książki/rejestru połączeń): ${meta.contact ?: "nieznany"}")
            appendLine("- właściciel telefonu (nagrywający): ${meta.myName}")
            appendLine("- długość: ${meta.duration}")
            appendLine()
            appendLine("<transkrypcja>")
            appendLine(transcript)
            appendLine("</transkrypcja>")
        }

        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .addUserMessage(user)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
        if (model in FALLBACK_MODELS) {
            // Gdy model odmówi (stop_reason=refusal), serwer automatycznie ponawia na modelu zapasowym.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        val message = try {
            client.messages().create(builder.build())
        } catch (e: AnthropicServiceException) {
            val code = e.statusCode()
            throw SummaryException("Claude API HTTP $code: ${e.message}", retryable = code == 429 || code >= 500)
        } catch (e: AnthropicIoException) {
            throw SummaryException("Błąd sieci (Claude): ${e.message}", retryable = true)
        } finally {
            client.close()
        }

        val stop = message.stopReason().map { it.toString() }.orElse("")
        if (stop == "refusal") throw SummaryException("Model odmówił analizy tej rozmowy", retryable = false)

        val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
        return extractJson(text) ?: throw SummaryException("Nieprawidłowa odpowiedź modelu (brak JSON)", retryable = true)
    }

    private fun extractJson(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    data class CallMeta(
        val date: String,
        val direction: String,
        val contact: String?,
        val myName: String,
        val duration: String,
    )

    companion object {
        private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5")

        private val SYSTEM_PROMPT = """
            Jesteś asystentem, który analizuje transkrypcje rozmów telefonicznych właściciela telefonu,
            żeby mógł później szybko przypomnieć sobie, o czym rozmawiał i co zostało ustalone.

            Transkrypcja pochodzi z automatycznego rozpoznawania mowy z podziałem na mówców (A, B, …),
            więc może zawierać błędy, przekręcone nazwy i źle przypisane fragmenty. Nagranie bywa robione
            mikrofonem telefonu, więc jedna strona rozmowy może być słabo słyszalna lub pominięta –
            jeśli tak jest, zaznacz to w polu "uwagi" i nie dopowiadaj treści, której nie ma w transkrypcji.

            Na podstawie kontekstu (kto się przedstawia, kto dzwoni, metadane) spróbuj ustalić, który mówca
            to właściciel telefonu, a który rozmówca. Gdy nie da się tego ustalić, zostaw ogólne etykiety.

            Odpowiedz wyłącznie jednym obiektem JSON (bez komentarzy i bez bloku kodu), w języku rozmowy,
            o strukturze:
            {
              "tytul": "krótki tytuł rozmowy, maks. 8 słów",
              "temat": "jedno zdanie – czego dotyczyła rozmowa",
              "mowcy": {"A": "np. Ja (Bartek)", "B": "np. Jan Kowalski – hydraulik"},
              "podsumowanie": "zwięzłe podsumowanie przebiegu i treści rozmowy (3–8 zdań)",
              "kluczowe_punkty": ["najważniejsze poruszone kwestie i informacje (kwoty, adresy, numery, nazwy)"],
              "ustalenia": ["co konkretnie zostało ustalone lub postanowione"],
              "zadania": [{"kto": "osoba odpowiedzialna", "co": "działanie do wykonania", "termin": "termin lub null"}],
              "terminy": [{"kiedy": "data/godzina", "co": "wydarzenie / spotkanie / deadline"}],
              "otwarte_kwestie": ["sprawy nierozstrzygnięte, pytania bez odpowiedzi"],
              "ton": "krótko: charakter rozmowy (np. rzeczowa, napięta, przyjacielska)",
              "uwagi": "uwagi o jakości nagrania/transkrypcji lub null"
            }
            Puste listy zostaw jako []. Nie wymyślaj ustaleń ani zadań, których nie było w rozmowie.
        """.trimIndent()
    }
}

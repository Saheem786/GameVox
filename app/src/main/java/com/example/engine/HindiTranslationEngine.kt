package com.example.engine

import android.util.Log
import android.util.LruCache
import com.example.data.ModelDownloadStatus
import com.example.data.TargetLanguage
import com.example.data.TranslatorStateRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray

/**
 * Real-time English-to-Hindi (and multi-language) translator combining:
 * 1. Google Translate Neural API (`translate.googleapis.com/translate_a/single`) for accurate,
 *    natural full-sentence translation of any arbitrary English game dialogue or UI text.
 * 2. Fast in-memory LRU cache (`translationCache`) so repeated subtitles translate in 0ms.
 * 3. Instant offline phrasebook & lexicon fallback when the device has no internet connection.
 */
object HindiTranslationEngine {

    private const val TAG = "HindiTranslationEngine"
    private val translationCache = LruCache<String, String>(250)

    private val MULTI_LANG_DIALOGUE_MAP: Map<TargetLanguage, Map<String, String>> = mapOf(
        TargetLanguage.HINDI to mapOf(
            "cybernetic guard: alert! target secured in sector 7. engage protocols." to
                "साइबरनेटिक गार्ड: अलर्ट! सेक्टर 7 में लक्ष्य सुरक्षित है। प्रोटोकॉल शुरू करें।",
            "alert! target secured in sector 7. engage protocols." to
                "अलर्ट! सेक्टर 7 में लक्ष्य सुरक्षित है। प्रोटोकॉल शुरू करें।",
            "ancient dragon: you dare challenge the keeper of the eternal flame?" to
                "प्राचीन ड्रैगन: क्या तुम अनंत ज्वाला के रक्षक को चुनौती देने का साहस करते हो?",
            "you dare challenge the keeper of the eternal flame?" to
                "क्या तुम अनंत ज्वाला के रक्षक को चुनौती देने का साहस करते हो?",
            "squad leader: hold your position! reinforcements arriving in thirty seconds." to
                "स्क्वाड लीडर: अपनी स्थिति संभालें! तीस सेकंड में सहायता पहुंच रही है।",
            "hold your position! reinforcements arriving in thirty seconds." to
                "अपनी स्थिति संभालें! तीस सेकंड में सहायता पहुंच रही है।",
            "mystic oracle: seek the hidden temple beyond the crimson mountains before dawn." to
                "रहस्यमयी भविष्यवक्ता: भोर होने से पहले लाल पहाड़ों के पार छिपे मंदिर को खोजें।",
            "mystic oracle: seek the hidden temple beyond thecrimson mountains before dawn." to
                "रहस्यमयी भविष्यवक्ता: भोर होने से पहले लाल पहाड़ों के पार छिपे मंदिर को खोजें।",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "भोर होने से पहले लाल पहाड़ों के पार छिपे मंदिर को खोजें।",
            "system terminal: security override complete. main vault door unlocked." to
                "सिस्टम टर्मिनल: सुरक्षा ओवरराइड पूरा हुआ। मुख्य तिजोरी का दरवाजा खुल गया है।",
            "security override complete. main vault door unlocked." to
                "सुरक्षा ओवरराइड पूरा हुआ। मुख्य तिजोरी का दरवाजा खुल गया है।"
        ),
        TargetLanguage.MARATHI to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "सावधान! सेक्टर 7 मध्ये लक्ष्य सुरक्षित आहे. प्रोटोकॉल सुरू करा.",
            "you dare challenge the keeper of the eternal flame?" to
                "तू अखंड ज्योतीच्या रक्षकाला आव्हान देण्याचे धाडस करतोस का?",
            "hold your position! reinforcements arriving in thirty seconds." to
                "आपली जागा सोडू नका! तीस सेकंदात मदत पोहोचत आहे.",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "पहाटेपूर्वी तांबड्या पर्वतांच्या पलीकडे लपलेले मंदिर शोधा.",
            "security override complete. main vault door unlocked." to
                "सुरक्षा ओव्हरराईड पूर्ण झाले. मुख्य तिजोरीचा दरवाजा उघडला आहे."
        ),
        TargetLanguage.BENGALI to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "সতর্কতা! সেক্টর ৭-এ লক্ষ্য সুরক্ষিত। প্রোটোকল চালু করুন।",
            "you dare challenge the keeper of the eternal flame?" to
                "তুমি কি চিরন্তন শিখার রক্ষককে চ্যালেঞ্জ করার সাহস করছ?",
            "hold your position! reinforcements arriving in thirty seconds." to
                "নিজের অবস্থান ধরে রাখুন! ত্রিশ সেকেন্ডের মধ্যে সাহায্য আসছে।",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "ভোর হওয়ার আগে রক্তিম পাহাড়ের ওপারে লুকানো মন্দিরটি খুঁজুন।",
            "security override complete. main vault door unlocked." to
                "নিরাপত্তা ওভাররাইড সম্পূর্ণ। প্রধান ভল্টের দরজা খুলে গেছে।"
        ),
        TargetLanguage.TAMIL to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "எச்சரிக்கை! பிரிவு 7-இல் இலக்கு பாதுகாக்கப்பட்டது. நெறிமுறைகளைத் தொடங்கவும்.",
            "you dare challenge the keeper of the eternal flame?" to
                "நித்திய சுடரின் காவலாளியை எதிர்க்க உனக்குத் துணிச்சல் உள்ளதா?",
            "hold your position! reinforcements arriving in thirty seconds." to
                "உங்கள் நிலையில் இருங்கள்! முப்பது வினாடிகளில் உதவிப்படை வருகிறது.",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "விடியும் முன் செந்நிற மலைகளுக்கு அப்பால் உள்ள மறைந்த கோவிலைத் தேடுங்கள்.",
            "security override complete. main vault door unlocked." to
                "பாதுகாப்பு மேலெழுதல் முடிந்தது. பிரதான பெட்டகக் கதவு திறக்கப்பட்டது."
        ),
        TargetLanguage.TELUGU to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "హెచ్చరిక! సెక్టార్ 7లో లక్ష్యం సురక్షితం చేయబడింది. ప్రోటోకాల్స్ ప్రారంభించండి.",
            "you dare challenge the keeper of the eternal flame?" to
                "శాశ్వత జ్వాల రక్షకుడిని సవాలు చేసే ధైర్యం నీకు ఉందా?",
            "hold your position! reinforcements arriving in thirty seconds." to
                "మీ స్థానంలోనే ఉండండి! ముప్పై సెకన్లలో బలగాలు వస్తున్నాయి.",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "తెల్లవారకముందే ఎర్రటి పర్వతాల అవతల దాగి ఉన్న ఆలయాన్ని వెతకండి.",
            "security override complete. main vault door unlocked." to
                "భద్రతా ఓవర్‌రైడ్ పూర్తయింది. ప్రధాన వాల్ట్ తలుపు తెరవబడింది."
        ),
        TargetLanguage.GUJARATI to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "ચેતવણી! સેક્ટર 7 માં લક્ષ્ય સુરક્ષિત છે. પ્રોટોકોલ શરૂ કરો.",
            "you dare challenge the keeper of the eternal flame?" to
                "શું તું અખંડ જ્યોતના રક્ષકને પડકારવાની હિંમત કરે છે?",
            "hold your position! reinforcements arriving in thirty seconds." to
                "તમારી સ્થિતિ જાળવી રાખો! ત્રીસ સેકન્ડમાં મદદ પહોંચી રહી છે.",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "પરોઢ થતાં પહેલાં લાલ પર્વતોની પેલે પાર છુપાયેલા મંદિરને શોધો.",
            "security override complete. main vault door unlocked." to
                "સુરક્ષા ઓવરરાઈડ પૂર્ણ. મુખ્ય તિજોરીનો દરવાજો ખુલી ગયો છે."
        ),
        TargetLanguage.KANNADA to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "ಎಚ್ಚರಿಕೆ! ಸೆಕ್ಟರ್ 7 ರಲ್ಲಿ ಗುರಿ ಸುರಕ್ಷിതವಾಗಿದೆ. ಪ್ರೊಟೊಕಾಲ್ ಪ್ರಾರಂಭಿಸಿ.",
            "you dare challenge the keeper of the eternal flame?" to
                "ಶಾಶ್ವತ ಜ್ವಾಲೆಯ ರಕ್ಷಕನಿಗೆ ಸವಾಲು ಹಾಕುವ ಧೈರ್ಯ ನಿನಗಿದೆಯೇ?",
            "hold your position! reinforcements arriving in thirty seconds." to
                "ನಿಮ್ಮ ಸ್ಥಾನದಲ್ಲಿರಿ! ಮೂವತ್ತು ಸೆಕೆಂಡುಗಳಲ್ಲಿ ಹೆಚ್ಚುವರಿ ಪಡೆ ಬರುತ್ತಿದೆ.",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "ಮುಂಜಾನೆಗೆ ಮೊದಲು ಕೆಂಪು ಪರ್ವತಗಳ ಆಚೆ ಇರುವ ಗುಪ್ತ ದೇವಾಲಯವನ್ನು ಹುಡುಕಿ.",
            "security override complete. main vault door unlocked." to
                "ಭದ್ರತಾ ಓವರ್‌ರೈಡ್ ಪೂರ್ಣಗೊಂಡಿದೆ. ಮುಖ್ಯ ತಿಜೋರಿ ಬಾಗಿಲು ತೆರೆದಿದೆ."
        ),
        TargetLanguage.URDU to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "خبردار! سیکٹر 7 میں ہدف محفوظ ہے۔ پروٹوکول شروع کریں۔",
            "you dare challenge the keeper of the eternal flame?" to
                "کیا تم ابدی شعلے کے محافظ کو للکارنے کی ہمت رکھتے ہو؟",
            "hold your position! reinforcements arriving in thirty seconds." to
                "اپنی پوزیشن سنبھالیں! تیس سیکنڈ میں کمک پہنچ رہی ہے۔",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "طلوع فجر سے پہلے سرخ پہاڑوں کے پار چھپے مندر کو تلاش کریں۔",
            "security override complete. main vault door unlocked." to
                "سیکیورٹی اوور رائیڈ مکمل۔ مرکزی تجوری کا دروازہ کھل گیا ہے۔"
        ),
        TargetLanguage.SPANISH to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "¡Alerta! Objetivo asegurado en el sector 7. Iniciar protocolos.",
            "you dare challenge the keeper of the eternal flame?" to
                "¿Te atreves a desafiar al guardián de la llama eterna?",
            "hold your position! reinforcements arriving in thirty seconds." to
                "¡Mantengan su posición! Los refuerzos llegarán en treinta segundos.",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "Busca el templo oculto más allá de las montañas carmesí antes del amanecer.",
            "security override complete. main vault door unlocked." to
                "Anulación de seguridad completa. Puerta de la bóveda principal desbloqueada."
        ),
        TargetLanguage.JAPANESE to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "警告！セクター7でターゲットを確保。プロトコルを開始せよ。",
            "you dare challenge the keeper of the eternal flame?" to
                "永遠の炎の守護者に挑むというのか？",
            "hold your position! reinforcements arriving in thirty seconds." to
                "持ち場を維持せよ！30秒後に増援が到着する。",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "夜明け前に紅の山々の向こうにある隠された神殿を探せ。",
            "security override complete. main vault door unlocked." to
                "セキュリティ解除完了。メイン金庫の扉がロック解除されました。"
        )
    )

    private val PHRASE_REPLACEMENTS = listOf(
        "cybernetic guard" to "साइबरनेटिक गार्ड",
        "ancient dragon" to "प्राचीन ड्रैगन",
        "squad leader" to "स्क्वाड लीडर",
        "mystic oracle" to "रहस्यमयी भविष्यवक्ता",
        "system terminal" to "सिस्टम टर्मिनल",
        "target secured" to "लक्ष्य सुरक्षित है",
        "engage protocols" to "प्रोटोकॉल शुरू करें",
        "hold your position" to "अपनी स्थिति संभालें",
        "reinforcements arriving" to "सहायता पहुंच रही है",
        "thirty seconds" to "तीस सेकंड",
        "eternal flame" to "अनंत ज्वाला",
        "hidden temple" to "छिपे मंदिर",
        "crimson mountains" to "लाल पहाड़ों",
        "before dawn" to "भोर होने से पहले",
        "security override complete" to "सुरक्षा ओवरराइड पूरा हुआ",
        "main vault door unlocked" to "मुख्य तिजोरी का दरवाजा खुल गया है",
        "game over" to "खेल समाप्त",
        "mission complete" to "मिशन पूरा हुआ",
        "mission failed" to "मिशन असफल रहा",
        "new objective" to "नया लक्ष्य",
        "watch out" to "सावधान रहें",
        "follow me" to "मेरे पीछे आओ",
        "need backup" to "मदद चाहिए",
        "take cover" to "आड़ लो",
        "open the door" to "दरवाजा खोलो",
        "find the key" to "चाबी ढूंढो",
        "boss defeated" to "बॉस पराजित हुआ",
        "level up" to "स्तर बढ़ गया",
        "health low" to "स्वास्थ्य कम है",
        "out of ammo" to "गोली खत्म हो गई",
        "press start" to "शुरू करने के लिए दबाएं",
        "loading game" to "गेम लोड हो रहा है",
        "talk to" to "से बात करें",
        "pick up" to "उठाएं",
        "inventory full" to "इन्वेंटरी भरी हुई है"
    )

    private val HINDI_WORD_LEXICON = mapOf(
        "i" to "मैं", "you" to "तुम", "we" to "हम", "they" to "वे", "he" to "वह", "she" to "वह",
        "it" to "यह", "this" to "यह", "that" to "वह", "the" to "", "a" to "एक", "an" to "एक",
        "is" to "है", "are" to "हैं", "was" to "था", "were" to "थे", "will" to "गा",
        "can" to "सकते हैं", "must" to "जरूर", "in" to "में", "on" to "पर", "at" to "पर",
        "to" to "को", "from" to "से", "with" to "के साथ", "without" to "के बिना", "of" to "का",
        "for" to "के लिए", "and" to "और", "or" to "या", "but" to "लेकिन", "not" to "नहीं",
        "no" to "नहीं", "yes" to "हाँ", "hello" to "नमस्ते", "welcome" to "स्वागत है",
        "alert" to "अलर्ट", "warning" to "चेतावनी", "danger" to "खतरा", "safe" to "सुरक्षित",
        "secured" to "सुरक्षित", "target" to "लक्ष्य", "sector" to "सेक्टर", "engage" to "शुरू करें",
        "protocol" to "प्रोटोकॉल", "protocols" to "प्रोटोकॉल", "guard" to "गार्ड", "dragon" to "ड्रैगन",
        "ancient" to "प्राचीन", "dare" to "साहस", "challenge" to "चुनौती", "keeper" to "रक्षक",
        "eternal" to "अनंत", "flame" to "ज्वाला", "fire" to "आग", "water" to "पानी", "wind" to "हवा",
        "earth" to "पृथ्वी", "shadow" to "छाया", "light" to "प्रकाश", "dark" to "अंधेरा",
        "night" to "रात", "day" to "दिन", "dawn" to "भोर", "hold" to "संभालें", "your" to "अपना",
        "my" to "मेरा", "our" to "हमारा", "position" to "स्थिति", "reinforcements" to "सहायता",
        "arriving" to "पहुंच रही है", "seconds" to "सेकंड", "minutes" to "मिनट", "seek" to "खोजें",
        "find" to "ढूंढें", "hidden" to "छिपा हुआ", "temple" to "मंदिर", "beyond" to "के पार",
        "crimson" to "लाल", "mountain" to "पहाड़", "mountains" to "पहाड़ों", "before" to "से पहले",
        "after" to "के बाद", "security" to "सुरक्षा", "override" to "ओवरराइड", "complete" to "पूरा हुआ",
        "completed" to "पूरा हुआ", "main" to "मुख्य", "vault" to "तिजोरी", "door" to "दरवाजा",
        "unlocked" to "खुल गया", "locked" to "बंद है", "open" to "खोलें", "close" to "बंद करें",
        "attack" to "हमला करें", "defend" to "रक्षा करें", "retreat" to "पीछे हटें", "run" to "भागो",
        "stop" to "रुको", "wait" to "प्रतीक्षा करो", "go" to "जाओ", "come" to "आओ", "help" to "मदद",
        "enemy" to "दुश्मन", "enemies" to "दुश्मन", "ally" to "मित्र", "friend" to "दोस्त",
        "leader" to "लीडर", "squad" to "स्क्वाड", "soldier" to "सैनिक", "warrior" to "योद्धा",
        "king" to "राजा", "queen" to "रानी", "sword" to "तलवार", "shield" to "कवच", "armor" to "कवच",
        "weapon" to "हथियार", "ammo" to "गोला-बारूद", "health" to "स्वास्थ्य", "power" to "शक्ति",
        "energy" to "ऊर्जा", "magic" to "जादू", "spell" to "मंत्र", "quest" to "अभियान",
        "mission" to "मिशन", "victory" to "विजय", "defeat" to "पराजय", "ready" to "तैयार",
        "now" to "अभी", "here" to "यहाँ", "there" to "वहाँ", "where" to "कहाँ", "what" to "क्या",
        "who" to "कौन", "why" to "क्यों", "how" to "कैसे", "one" to "एक", "two" to "दो",
        "three" to "तीन", "four" to "चार", "five" to "पाँच", "six" to "छह", "seven" to "सात",
        "eight" to "आठ", "nine" to "नौ", "ten" to "दस", "twenty" to "बीस", "thirty" to "तीस"
    )

    /**
     * Translates [englishText] into [targetLanguage] (Hindi by default):
     * 1. Checks fast LRU cache & exact preset map.
     * 2. Calls Google Translate API (`translate.googleapis.com`) for natural full-sentence translation.
     * 3. Falls back to offline phrasebook & lexicon if network is unreachable.
     */
    fun translate(
        englishText: String,
        targetLanguage: TargetLanguage = TargetLanguage.HINDI
    ): String {
        val clean = englishText.trim()
        if (clean.isEmpty()) return ""

        val normalized = clean.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val cacheKey = "${targetLanguage.code}:$normalized"

        translationCache.get(cacheKey)?.let { return it }

        // 1. Check built-in preset dialogues first for 0ms instant response
        val langDialogues = MULTI_LANG_DIALOGUE_MAP[targetLanguage]
        if (langDialogues != null) {
            langDialogues[normalized]?.let {
                translationCache.put(cacheKey, it)
                return it
            }
            for ((pattern, translated) in langDialogues) {
                if (normalized.contains(pattern) || pattern.contains(normalized)) {
                    translationCache.put(cacheKey, translated)
                    return translated
                }
            }
        }

        // 2. Call Google Translate API for full-sentence neural translation of any game text
        val onlineTranslated = translateViaGoogleApi(clean, targetLanguage.code)
        if (!onlineTranslated.isNullOrBlank()) {
            val result = onlineTranslated.trim()
            translationCache.put(cacheKey, result)
            TranslatorStateRepository.setModelStatus(ModelDownloadStatus.READY)
            return result
        }

        // 3. Offline fallback if device has no internet connection
        val offlineResult = translateOfflineFallback(clean, targetLanguage)
        translationCache.put(cacheKey, offlineResult)
        return offlineResult
    }

    /**
     * Queries Google Translate's `translate_a/single` JSON endpoint to translate arbitrary
     * English game sentences into fluent Hindi (or any selected target language).
     */
    private fun translateViaGoogleApi(text: String, targetLangCode: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val urlString = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=$targetLangCode&dt=t&q=$encodedText"
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                    .use { it.readText() }
                val rootArray = JSONArray(response)
                val sentencesArray = rootArray.optJSONArray(0) ?: return null
                val builder = StringBuilder()
                for (i in 0 until sentencesArray.length()) {
                    val chunk = sentencesArray.optJSONArray(i)
                    val piece = chunk?.optString(0)
                    if (!piece.isNullOrEmpty()) {
                        builder.append(piece)
                    }
                }
                val finalOutput = builder.toString().trim()
                if (finalOutput.isNotEmpty()) finalOutput else null
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google Translate API offline or unreachable, using local engine")
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun translateOfflineFallback(
        clean: String,
        targetLanguage: TargetLanguage
    ): String {
        if (clean.contains(":")) {
            val parts = clean.split(":", limit = 2)
            val speaker = parts[0].trim()
            val body = parts[1].trim()
            if (speaker.isNotEmpty() && body.isNotEmpty() && speaker.split(" ").size <= 4) {
                val translatedSpeaker = translateHindiClause(speaker)
                val translatedBody = translateHindiClause(body)
                return "$translatedSpeaker: $translatedBody"
            }
        }
        return translateHindiClause(clean)
    }

    private fun translateHindiClause(clause: String): String {
        val normalizedClause = clause.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        MULTI_LANG_DIALOGUE_MAP[TargetLanguage.HINDI]?.get(normalizedClause)?.let { return it }

        var working = normalizedClause
        for ((engPhrase, hindiPhrase) in PHRASE_REPLACEMENTS) {
            if (working.contains(engPhrase)) {
                working = working.replace(engPhrase, hindiPhrase)
            }
        }

        val tokens = working.split(Regex("\\s+"))
        val translatedTokens = mutableListOf<String>()

        for (rawToken in tokens) {
            if (rawToken.isBlank()) continue

            if (rawToken.any { it in '\u0900'..'\u097F' }) {
                translatedTokens.add(rawToken)
                continue
            }

            val leadingPunct = rawToken.takeWhile { !it.isLetterOrDigit() }
            val trailingPunct = rawToken.takeLastWhile { !it.isLetterOrDigit() }
            val core = rawToken.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)

            if (core.isEmpty()) {
                translatedTokens.add(rawToken)
                continue
            }

            if (core.all { it.isDigit() }) {
                translatedTokens.add("$leadingPunct$core$trailingPunct")
                continue
            }

            val mapped = HINDI_WORD_LEXICON[core]
            if (mapped != null) {
                if (mapped.isNotEmpty()) {
                    val hindiPunct = trailingPunct.replace(".", "।")
                    translatedTokens.add("$leadingPunct$mapped$hindiPunct")
                }
            } else {
                val transliterated = transliterateToDevanagari(core)
                val hindiPunct = trailingPunct.replace(".", "।")
                translatedTokens.add("$leadingPunct$transliterated$hindiPunct")
            }
        }

        return translatedTokens
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun transliterateToDevanagari(word: String): String {
        val w = word.lowercase(Locale.ROOT)
        val sb = StringBuilder()
        var i = 0
        while (i < w.length) {
            val twoChar = if (i + 1 < w.length) w.substring(i, i + 2) else ""
            when (twoChar) {
                "sh" -> { sb.append("श"); i += 2; continue }
                "ch" -> { sb.append("च"); i += 2; continue }
                "th" -> { sb.append("थ"); i += 2; continue }
                "ph" -> { sb.append("फ"); i += 2; continue }
                "bh" -> { sb.append("भ"); i += 2; continue }
                "dh" -> { sb.append("ध"); i += 2; continue }
                "gh" -> { sb.append("घ"); i += 2; continue }
                "kh" -> { sb.append("ख"); i += 2; continue }
                "aa", "ee", "oo" -> {
                    sb.append(if (twoChar == "aa") "आ" else if (twoChar == "ee") "ई" else "ऊ")
                    i += 2
                    continue
                }
            }
            when (w[i]) {
                'a' -> sb.append(if (i == 0) "अ" else "ा")
                'b' -> sb.append("ब")
                'c' -> sb.append("क")
                'd' -> sb.append("ड")
                'e' -> sb.append(if (i == 0) "ए" else "े")
                'f' -> sb.append("फ")
                'g' -> sb.append("ग")
                'h' -> sb.append("ह")
                'i' -> sb.append(if (i == 0) "इ" else "ि")
                'j' -> sb.append("ज")
                'k' -> sb.append("क")
                'l' -> sb.append("ल")
                'm' -> sb.append("म")
                'n' -> sb.append("न")
                'o' -> sb.append(if (i == 0) "ओ" else "ो")
                'p' -> sb.append("प")
                'q' -> sb.append("क")
                'r' -> sb.append("र")
                's' -> sb.append("स")
                't' -> sb.append("ट")
                'u' -> sb.append(if (i == 0) "उ" else "ु")
                'v', 'w' -> sb.append("व")
                'x' -> sb.append("क्स")
                'y' -> sb.append("य")
                'z' -> sb.append("ज़")
                else -> sb.append(w[i])
            }
            i++
        }
        return sb.toString()
    }
}

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
 * Real-time English-to-Modern Casual Hindi (Bolchal ki Hindi / Hinglish) translator combining:
 * 1. Google Translate Neural API (`translate.googleapis.com/translate_a/single`) followed by
 *    [convertFormalHindiToModernCasual] so stiff/formal Sanskritized Hindi (like "मेरे पिता का अंतिम संस्कार")
 *    is automatically transformed into natural, daily-use modern Hindi (like "मेरे पापा का फ्यूनरल").
 * 2. Fast in-memory LRU cache (`translationCache`) so repeated subtitles translate in 0ms.
 * 3. Instant offline conversational Hindi phrasebook & lexicon fallback when offline.
 */
object HindiTranslationEngine {

    private const val TAG = "HindiTranslationEngine"
    private val translationCache = LruCache<String, String>(250)

    private val MULTI_LANG_DIALOGUE_MAP: Map<TargetLanguage, Map<String, String>> = mapOf(
        TargetLanguage.HINDI to mapOf(
            "my father's funeral" to "मेरे पापा का फ्यूनरल",
            "at my father's funeral" to "मेरे पापा के फ्यूनरल पे",
            "i was at my father's funeral." to "मैं अपने पापा के फ्यूनरल पे था।",
            "cybernetic guard: alert! target secured in sector 7. engage protocols." to
                "साइबरनेटिक गार्ड: अलर्ट! सेक्टर 7 में टारगेट सेफ है। प्रोटोकॉल चालू करो।",
            "alert! target secured in sector 7. engage protocols." to
                "अलर्ट! सेक्टर 7 में टारगेट सेफ है। प्रोटोकॉल चालू करो।",
            "ancient dragon: you dare challenge the keeper of the eternal flame?" to
                "एन्शिएंट ड्रैगन: तुम्हारी हिम्मत कैसे हुई एटर्नल फ्लेम के गार्जियन को चैलेंज करने की?",
            "you dare challenge the keeper of the eternal flame?" to
                "तुम्हारी हिम्मत कैसे हुई एटर्नल फ्लेम के गार्जियन को चैलेंज करने की?",
            "squad leader: hold your position! reinforcements arriving in thirty seconds." to
                "स्क्वाड लीडर: अपनी पोज़िशन होल्ड करो! 30 सेकंड में बैकअप आ रहा है।",
            "hold your position! reinforcements arriving in thirty seconds." to
                "अपनी पोज़िशन होल्ड करो! 30 सेकंड में बैकअप आ रहा है।",
            "mystic oracle: seek the hidden temple beyond the crimson mountains before dawn." to
                "मिस्टिक ओरेकल: सुबह होने से पहले लाल पहाड़ों के उस पार छुपा हुआ मंदिर ढूंढो।",
            "mystic oracle: seek the hidden temple beyond thecrimson mountains before dawn." to
                "मिस्टिक ओरेकल: सुबह होने से पहले लाल पहाड़ों के उस पार छुपा हुआ मंदिर ढूंढो।",
            "seek the hidden temple beyond the crimson mountains before dawn." to
                "सुबह होने से पहले लाल पहाड़ों के उस पार छुपा हुआ मंदिर ढूंढो।",
            "system terminal: security override complete. main vault door unlocked." to
                "सिस्टम टर्मिनल: सिक्योरिटी ओवरराइड पूरा हो गया। मेन वॉल्ट का गेट खुल गया है।",
            "security override complete. main vault door unlocked." to
                "सिक्योरिटी ओवरराइड पूरा हो गया। मेन वॉल्ट का गेट खुल गया है।",
            "hold the northern gate!" to
                "नॉर्थ वाला गेट संभाल के रखो!",
            "the ancient portal is open." to
                "पुराना पोर्टल खुल गया है।"
        ),
        TargetLanguage.MARATHI to mapOf(
            "alert! target secured in sector 7. engage protocols." to
                "सावधान! सेक्टर 7 मध्ये टार्गेट सुरक्षित आहे. प्रोटोकॉल सुरू करा.",
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

    // Casual, everyday Indian Hindi / Hinglish phrase replacements
    private val PHRASE_REPLACEMENTS = listOf(
        "my father's funeral" to "मेरे पापा का फ्यूनरल",
        "father's funeral" to "पापा के फ्यूनरल",
        "mother's funeral" to "मम्मी के फ्यूनरल",
        "cybernetic guard" to "साइबरनेटिक गार्ड",
        "ancient dragon" to "एन्शिएंट ड्रैगन",
        "squad leader" to "स्क्वाड लीडर",
        "mystic oracle" to "मिस्टिक ओरेकल",
        "system terminal" to "सिस्टम टर्मिनल",
        "target secured" to "टारगेट सेफ है",
        "engage protocols" to "प्रोटोकॉल चालू करो",
        "hold your position" to "अपनी पोज़िशन होल्ड करो",
        "reinforcements arriving" to "बैकअप आ रहा है",
        "thirty seconds" to "30 सेकंड",
        "eternal flame" to "एटर्नल फ्लेम",
        "hidden temple" to "छुपा हुआ मंदिर",
        "crimson mountains" to "लाल पहाड़ों",
        "before dawn" to "सुबह होने से पहले",
        "security override complete" to "सिक्योरिटी ओवरराइड पूरा हो गया",
        "main vault door unlocked" to "मेन वॉल्ट का गेट खुल गया है",
        "game over" to "गेम ओवर",
        "mission complete" to "मिशन पूरा हो गया",
        "mission failed" to "मिशन फेल हो गया",
        "new objective" to "नया मिशन टारगेट",
        "watch out" to "अरे संभल के",
        "follow me" to "मेरे पीछे आओ",
        "need backup" to "बैकअप चाहिए यार",
        "take cover" to "कवर ले लो",
        "open the door" to "गेट खोलो",
        "find the key" to "चाबी ढूंढो",
        "boss defeated" to "बॉस को हरा दिया",
        "level up" to "लेवल अप हो गया",
        "health low" to "हेल्थ कम बची है",
        "out of ammo" to "गोलियां खत्म हो गईं",
        "press start" to "स्टार्ट दबाओ",
        "loading game" to "गेम लोड हो रहा है",
        "talk to" to "से बात करो",
        "pick up" to "उठा लो",
        "inventory full" to "इन्वेंटरी फुल है"
    )

    // Post-processor rules converting stiff/formal Sanskritized Hindi (from Google Translate)
    // into casual, daily-use conversational Hindi (Bolchal ki Hindi / Hinglish).
    private val FORMAL_TO_CASUAL_HINDI_RULES = listOf(
        "मेरे पिता के अंतिम संस्कार" to "मेरे पापा के फ्यूनरल",
        "मेरे पिता का अंतिम संस्कार" to "मेरे पापा का फ्यूनरल",
        "पिता के अंतिम संस्कार" to "पापा के फ्यूनरल",
        "पिता का अंतिम संस्कार" to "पापा का फ्यूनरल",
        "अंतिम संस्कार में" to "फ्यूनरल पे",
        "अंतिम संस्कार पर" to "फ्यूनरल पे",
        "अंतिम संस्कार" to "फ्यूनरल",
        "दाह संस्कार" to "फ्यूनरल",
        "मेरे पिताजी" to "मेरे पापा",
        "मेरे पिता" to "मेरे पापा",
        "तुम्हारे पिता" to "तुम्हारे पापा",
        "आपके पिता" to "आपके पापा",
        "पिताजी" to "पापा",
        "मेरी माताजी" to "मेरी मम्मी",
        "मेरी माता" to "मेरी मम्मी",
        "माताजी" to "मम्मी",
        "भ्राता" to "भाई",
        "प्रतीक्षा कर रहा" to "वेट कर रहा",
        "प्रतीक्षा कर रही" to "वेट कर रही",
        "प्रतीक्षा करें" to "वेट करो",
        "प्रतीक्षा करो" to "वेट करो",
        "प्रतीक्षा" to "इंतज़ार",
        "पहचान पत्र" to "आईडी",
        "सुरक्षा ओवरराइड" to "सिक्योरिटी ओवरराइड",
        "मुख्य तिजोरी का दरवाजा" to "मेन वॉल्ट का गेट",
        "मुख्य तिजोरी" to "मेन वॉल्ट",
        "लक्ष्य सुरक्षित है" to "टारगेट सेफ है",
        "अपनी स्थिति संभालें" to "अपनी पोज़िशन होल्ड करो",
        "स्थिति संभालें" to "पोज़िशन होल्ड करो",
        "प्रोटोकॉल शुरू करें" to "प्रोटोकॉल चालू करो",
        "सहायता पहुंच रही है" to "बैकअप आ रहा है",
        "सहायता आ रही है" to "मदद आ रही है",
        "सहायता करें" to "मदद करो",
        "सहायता" to "मदद",
        "साहस करते हो" to "हिम्मत कर रहे हो",
        "साहस" to "हिम्मत",
        "चुनौती देने का" to "चैलेंज करने की",
        "चुनौती" to "चैलेंज",
        "भोर होने से पहले" to "सुबह होने से पहले",
        "रहस्यमयी भविष्यवक्ता" to "मिस्टिक ओरेकल",
        "प्राचीन ड्रैगन" to "एन्शिएंट ड्रैगन",
        "अनंत ज्वाला" to "एटर्नल फ्लेम",
        "के रक्षक" to "के गार्जियन",
        "सावधान रहें" to "संभल के रहो",
        "सावधान" to "संभल के",
        "कृपया" to "प्लीज़",
        "धन्यवाद" to "थैंक्स",
        "क्षमा करें" to "सॉरी",
        "माफ़ कीजिए" to "सॉरी यार",
        "अनुमति" to "परमिशन",
        "आवश्यकता है" to "ज़रूरत है",
        "आवश्यक" to "ज़रूरी",
        "अनिवार्य" to "ज़रूरी",
        "असंभव" to "इम्पॉसिबल",
        "संभवतः" to "शायद",
        "निश्चित रूप से" to "पक्का",
        "शीघ्र" to "जल्दी",
        "तत्काल" to "अभी तुरंत",
        "प्रारंभ करें" to "शुरू करो",
        "आरंभ करें" to "शुरू करो",
        "समाप्त हो गया" to "खत्म हो गया",
        "समाप्त" to "खत्म",
        "पराजित किया" to "हरा दिया",
        "पराजित हुआ" to "हार गया",
        "पराजित" to "डिफीट",
        "विजय" to "जीत",
        "पराजय" to "हार",
        "चिकित्सक" to "डॉक्टर",
        "औषधि" to "दवाई",
        "संदेश" to "मैसेज",
        "दूरभाष" to "फ़ोन",
        "संगणक" to "कंप्यूटर",
        "त्रुटि" to "एरर",
        "चेतावनी" to "वॉर्निंग",
        "खोजें" to "ढूंढो",
        "ढूंढें" to "ढूंढो",
        "जाएं" to "जाओ",
        "आएं" to "आओ",
        "दिखाएं" to "दिखाओ",
        "बताएं" to "बताओ",
        "खोलें" to "खोलो",
        "बंद करें" to "बंद करो"
    )

    private val HINDI_WORD_LEXICON = mapOf(
        "i" to "मैं", "you" to "तुम", "we" to "हम", "they" to "वो लोग", "he" to "वो", "she" to "वो",
        "it" to "ये", "this" to "ये", "that" to "वो", "the" to "", "a" to "एक", "an" to "एक",
        "is" to "है", "are" to "हैं", "was" to "था", "were" to "थे", "will" to "गा",
        "can" to "सकते हो", "must" to "पक्का", "in" to "में", "on" to "पे", "at" to "पे",
        "to" to "को", "from" to "से", "with" to "के साथ", "without" to "के बिना", "of" to "का",
        "for" to "के लिए", "and" to "और", "or" to "या", "but" to "पर", "not" to "नहीं",
        "no" to "नहीं", "yes" to "हाँ", "hello" to "हेलो", "welcome" to "वेलकम",
        "father" to "पापा", "father's" to "पापा का", "dad" to "पापा", "mother" to "मम्मी",
        "mom" to "मम्मी", "brother" to "भाई", "sister" to "बहन", "funeral" to "फ्यूनरल",
        "alert" to "अलर्ट", "warning" to "वॉर्निंग", "danger" to "खतरा", "safe" to "सेफ",
        "secured" to "सेफ", "target" to "टारगेट", "sector" to "सेक्टर", "engage" to "चालू करो",
        "protocol" to "प्रोटोकॉल", "protocols" to "प्रोटोकॉल", "guard" to "गार्ड", "dragon" to "ड्रैगन",
        "ancient" to "पुराना", "dare" to "हिम्मत", "challenge" to "चैलेंज", "keeper" to "गार्जियन",
        "eternal" to "एटर्नल", "flame" to "फ्लेम", "fire" to "आग", "water" to "पानी", "wind" to "हवा",
        "earth" to "धरती", "shadow" to "साया", "light" to "लाइट", "dark" to "अंधेरा",
        "night" to "रात", "day" to "दिन", "dawn" to "सुबह", "hold" to "होल्ड करो", "your" to "अपना",
        "my" to "मेरे", "our" to "हमारा", "position" to "पोज़िशन", "reinforcements" to "बैकअप",
        "arriving" to "आ रहा है", "seconds" to "सेकंड", "minutes" to "मिनट", "seek" to "ढूंढो",
        "find" to "ढूंढो", "hidden" to "छुपा हुआ", "temple" to "मंदिर", "beyond" to "के उस पार",
        "crimson" to "लाल", "mountain" to "पहाड़", "mountains" to "पहाड़ों", "before" to "से पहले",
        "after" to "के बाद", "security" to "सिक्योरिटी", "override" to "ओवरराइड", "complete" to "पूरा हो गया",
        "completed" to "पूरा हो गया", "main" to "मेन", "vault" to "वॉल्ट", "door" to "गेट",
        "unlocked" to "खुल गया", "locked" to "लॉक है", "open" to "खोलो", "close" to "बंद करो",
        "attack" to "अटैक करो", "defend" to "डिफेंड करो", "retreat" to "पीछे हटो", "run" to "भागो",
        "stop" to "रुको", "wait" to "वेट करो", "go" to "चलो", "come" to "आओ", "help" to "मदद",
        "enemy" to "दुश्मन", "enemies" to "दुश्मन", "ally" to "टीममेट", "friend" to "दोस्त",
        "leader" to "लीडर", "squad" to "स्क्वाड", "soldier" to "सोल्जर", "warrior" to "फाइटर",
        "king" to "किंग", "queen" to "क्वीन", "sword" to "तलवार", "shield" to "शील्ड", "armor" to "आर्मर",
        "weapon" to "वेपन", "ammo" to "एम्मो", "health" to "हेल्थ", "power" to "पावर",
        "energy" to "एनर्जी", "magic" to "मैजिक", "spell" to "स्पेल", "quest" to "क्वेस्ट",
        "mission" to "मिशन", "victory" to "जीत", "defeat" to "हार", "ready" to "रेडी",
        "now" to "अभी", "here" to "यहाँ", "there" to "वहाँ", "where" to "कहाँ", "what" to "क्या",
        "who" to "कौन", "why" to "क्यों", "how" to "कैसे", "one" to "एक", "two" to "दो",
        "three" to "तीन", "four" to "चार", "five" to "पाँच", "six" to "छह", "seven" to "सात",
        "eight" to "आठ", "nine" to "नौ", "ten" to "दस", "twenty" to "बीस", "thirty" to "तीस"
    )

    /**
     * Translates [englishText] into [targetLanguage] (Hindi by default), converting any formal
     * Sanskritized Hindi into natural, daily-use modern Hindi (casual Bolchal / Hinglish).
     */
    fun translate(
        englishText: String,
        targetLanguage: TargetLanguage = TargetLanguage.HINDI
    ): String {
        val clean = englishText.trim()
        if (clean.isEmpty()) return ""

        val normalized = clean.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val cacheKey = "${targetLanguage.code}_casual_v2:$normalized"

        translationCache.get(cacheKey)?.let { return it }

        // 1. Check built-in conversational presets first for 0ms instant response
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

        // 2. Call Google Translate API and normalize formal Hindi into daily-use modern casual Hindi
        val onlineTranslated = translateViaGoogleApi(clean, targetLanguage.code)
        if (!onlineTranslated.isNullOrBlank()) {
            val rawResult = onlineTranslated.trim()
            val finalResult = if (targetLanguage == TargetLanguage.HINDI) {
                convertFormalHindiToModernCasual(rawResult, normalized)
            } else {
                rawResult
            }
            translationCache.put(cacheKey, finalResult)
            TranslatorStateRepository.setModelStatus(ModelDownloadStatus.READY)
            return finalResult
        }

        // 3. Offline fallback using casual daily-use Hindi lexicon
        val offlineResult = translateOfflineFallback(clean, targetLanguage)
        val casualOffline = if (targetLanguage == TargetLanguage.HINDI) {
            convertFormalHindiToModernCasual(offlineResult, normalized)
        } else {
            offlineResult
        }
        translationCache.put(cacheKey, casualOffline)
        return casualOffline
    }

    /**
     * Post-processes formal Hindi sentences (e.g., "मेरे पिता का अंतिम संस्कार") into natural,
     * everyday spoken Indian Hindi / Hinglish (e.g., "मेरे पापा का फ्यूनरल").
     */
    fun convertFormalHindiToModernCasual(hindiText: String, originalEnglishLower: String = ""): String {
        var result = hindiText.trim()
        if (result.isEmpty()) return result

        // Apply multi-word and single-word formal -> casual bolchal replacements
        for ((formal, casual) in FORMAL_TO_CASUAL_HINDI_RULES) {
            if (result.contains(formal)) {
                result = result.replace(formal, casual)
            }
        }

        // Replace standalone formal "पिता" with "पापा" and "माता" with "मम्मी" on word boundaries
        result = result
            .replace(Regex("(?<=^|\\s)पिता(?=\\s|[।,.!?]|$)"), "पापा")
            .replace(Regex("(?<=^|\\s)माता(?=\\s|[।,.!?]|$)"), "मम्मी")
            .replace(Regex("(?<=^|\\s)मित्र(?=\\s|[।,.!?]|$)"), "दोस्त")
            .replace(Regex("(?<=^|\\s)द्वार(?=\\s|[।,.!?]|$)"), "गेट")

        // Context-aware English loanword preservation for common everyday words
        if (originalEnglishLower.contains("funeral") && !result.contains("फ्यूनरल")) {
            result = result.replace("संस्कार", "फ्यूनरल")
        }
        if (originalEnglishLower.contains("father") && !result.contains("पापा")) {
            result = result.replace("पिता", "पापा")
        }

        return result.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Queries Google Translate's `translate_a/single` JSON endpoint to translate arbitrary
     * English game sentences into Hindi (or any selected target language).
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

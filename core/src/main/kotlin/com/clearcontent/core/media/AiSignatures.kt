package com.clearcontent.core.media

/**
 * Known provenance vocabularies and generator names written into media metadata.
 * Matching is only performed on metadata payloads (never on compressed pixel data),
 * except for the long, distinctive [STRONG_TOKENS] which are safe to search anywhere.
 */
object AiSignatures {

    /** IPTC Digital Source Type values that describe synthetic or AI-assisted media. */
    val AI_SOURCE_TYPES = listOf(
        "compositeWithTrainedAlgorithmicMedia",
        "trainedAlgorithmicMedia",
        "algorithmicallyEnhanced",
        "compositeSynthetic",
        "algorithmicMedia",
        "dataDrivenMedia",
    )

    data class Generator(val name: String, val patterns: List<Regex>)

    private fun gen(name: String, vararg patterns: String) =
        Generator(name, patterns.map { Regex(it, RegexOption.IGNORE_CASE) })

    val GENERATORS: List<Generator> = listOf(
        gen("OpenAI / ChatGPT", "\\bopenai\\b", "chatgpt", "dall[-·.\\s]?e", "gpt-image", "gpt-4o", "\\bsora\\b"),
        gen("Google Gemini / Imagen", "\\bgemini\\b", "google imagen", "\\bimagen[\\s-]?[234]\\b", "nano[\\s_-]?banana", "google ai", "synthid", "google veo", "\\bveo[\\s-]?[23]\\b", "google deepmind", "made with google"),
        gen("Google Фото (ИИ)", "magic editor", "magic eraser", "edited with google ai"),
        gen("Adobe Firefly", "firefly", "generative fill", "generative expand", "adobe gen"),
        gen("Microsoft Designer / Copilot", "bing image creator", "microsoft designer", "\\bcopilot\\b", "azure openai"),
        gen("Midjourney", "midjourney"),
        gen("Stable Diffusion", "stable[\\s_-]?diffusion", "stability\\.?ai", "\\bsdxl\\b", "automatic1111", "negative prompt:", "sampler:\\s", "cfg scale:"),
        gen("ComfyUI", "comfyui", "\"class_type\"", "ksampler"),
        gen("InvokeAI / Fooocus / NovelAI", "invokeai", "sd-metadata", "fooocus", "novelai", "\\bnai diffusion\\b"),
        gen("Flux / Black Forest Labs", "black forest labs", "\\bflux\\.?1\\b", "flux[\\s._-]?(?:pro|dev|schnell|kontext)"),
        gen("Meta AI", "meta ai", "imagine with meta", "\\bemu edit\\b"),
        gen("xAI Grok", "\\bgrok\\b", "\\bx\\.ai\\b", "grok imagine", "grok-\\d"),
        gen("Leonardo / Ideogram / Recraft", "leonardo\\.?ai", "ideogram", "recraft"),
        gen("Runway / Luma / Pika", "runwayml", "\\brunway\\b", "luma ai", "dream machine", "pika labs", "\\bpika\\b"),
        gen("Kling / Hailuo / метка AIGC (КНР)", "\\bkling\\b", "kuaishou", "hailuo", "minimax", "jimeng", "doubao", "seedream", "seedance", "bytedance", "tongyi", "wanx", "qwen", "hunyuan", "\\baigc\\b", "contentproducer", "contentpropagator", "produceid"),
        gen("Apple Image Playground", "image playground", "apple intelligence", "genmoji"),
        gen("Samsung Galaxy AI", "galaxy ai", "generative edit", "sketch to image"),
        gen("Другие ИИ-редакторы", "canva ai", "magic media", "krea\\.ai", "playground ai", "freepik ai", "picsart ai", "lensa ai", "remini", "faceapp", "topaz (?:photo|gigapixel|labs)", "luminar neo", "photoroom"),
        gen("Пометка «создано ИИ»", "\\bai[\\s-]generated\\b", "generated (?:by|with) ai\\b", "made with ai\\b", "created with ai\\b"),
    )

    /** Distinctive byte tokens that indicate provenance data no matter where they appear. */
    val STRONG_TOKENS: List<String> = listOf(
        "trainedAlgorithmicMedia", "compositeWithTrainedAlgorithmicMedia", "c2pa.actions", "c2pa.claim",
        "c2pa.signature", "contentauth", "urn:c2pa", "urn:uuid:c2pa", "Content Credentials",
    )

    /** ISO BMFF 'uuid' user types. */
    val C2PA_UUID = hex("d8fec3d61b0e483c92975828877ec481")
    val XMP_UUID = hex("be7acfcb97a942e89c71999491e3afac")

    fun matchGenerators(text: String): List<String> =
        GENERATORS.filter { g -> g.patterns.any { it.containsMatchIn(text) } }.map { it.name }

    // The lookbehind keeps "trainedAlgorithmicMedia" from matching inside "compositeWithTrainedAlgorithmicMedia".
    private val SOURCE_TYPE_PATTERNS = AI_SOURCE_TYPES.associateWith { Regex("(?<![A-Za-z])$it", RegexOption.IGNORE_CASE) }

    fun matchSourceTypes(text: String): List<String> =
        AI_SOURCE_TYPES.filter { SOURCE_TYPE_PATTERNS.getValue(it).containsMatchIn(text) }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

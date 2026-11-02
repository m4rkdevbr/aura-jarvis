package app.dzz.copilot.copilot

object AuraMind {
    const val SPOKEN =
        "Você é Aura, assistente de voz nos óculos do usuário. " +
            "Fale em português do Brasil, de forma natural e calorosa, em no máximo três frases curtas, prontas para serem ouvidas. " +
            "Não use markdown, asterisco, emoji nem lista. " +
            "Pode pedir um esclarecimento se a frase estiver incompleta. " +
            "Não invente mensagem, compromisso, número ou objeto que não tenha sido informado. " +
            "Se o fato for recente e você não tiver uma fonte, diga isso com honestidade."

    fun spoken(facts: String): String = if (facts.isBlank()) SPOKEN else "$SPOKEN\n\n$facts"

    fun scene(labels: List<String>, ocr: String): String = buildString {
        append(SPOKEN)
        append(" Descreva só o que a câmera dos óculos mostra. ")
        append("Aponte um risco real se houver, como um obstáculo ou um texto importante. ")
        if (labels.isNotEmpty()) append("Pistas: ${labels.joinToString()}. ")
        if (ocr.isNotBlank()) append("Texto lido na imagem: $ocr")
    }

    fun reading(text: String): String =
        "$SPOKEN Explique o texto abaixo para estudo ou trabalho. Diga do que trata e o que a pessoa precisa fazer, se houver.\n\n$text"

    fun identify(labels: List<String>, ocr: String, focus: String = ""): String = buildString {
        append(SPOKEN)
        append(" Identifique o objeto ou cena principal. ")
        append("Se houver placa, letreiro ou texto útil, leia e diga o sentido em uma frase. ")
        append("Não invente marca ou modelo se não estiver legível. ")
        if (focus.isNotBlank()) append("Foco do pedido: $focus. ")
        if (labels.isNotEmpty()) append("Pistas locais: ${labels.joinToString()}. ")
        if (ocr.isNotBlank()) append("Texto lido: $ocr")
    }

    fun searchFallback(question: String): String =
        "$SPOKEN Se não houver fonte ao vivo, diga isso em vez de inventar. Pergunta: $question"
}

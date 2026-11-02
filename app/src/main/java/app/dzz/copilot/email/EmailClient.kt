package app.dzz.copilot.email

import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Address
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.search.FlagTerm
import javax.mail.util.ByteArrayDataSource

/** IMAP and SMTP servers of a mailbox. */
data class MailServers(val imap: String, val imapPort: Int, val smtp: String, val smtpPort: Int, val smtpSsl: Boolean)

object EmailRules {
    /** Known providers by address domain; anything else tries imap./smtp. of the domain. */
    fun serversFor(address: String): MailServers {
        val domain = address.substringAfter('@', "").lowercase().trim()
        return when (domain) {
            "gmail.com", "googlemail.com" -> MailServers("imap.gmail.com", 993, "smtp.gmail.com", 465, true)
            "outlook.com", "hotmail.com", "live.com", "msn.com" ->
                MailServers("outlook.office365.com", 993, "smtp.office365.com", 587, false)
            "yahoo.com", "yahoo.com.br" -> MailServers("imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 465, true)
            "icloud.com", "me.com" -> MailServers("imap.mail.me.com", 993, "smtp.mail.me.com", 587, false)
            else -> MailServers("imap.$domain", 993, "smtp.$domain", 465, true)
        }
    }

    fun isAddress(text: String): Boolean = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(text.trim())

    /** Plain text from an HTML body: tags out, entities for spaces and quotes, blank runs squeezed. */
    fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("[ \\t]+"), " ")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n")
}

data class MailSummary(val uid: Long, val from: String, val subject: String, val date: Date?, val unread: Boolean, val preview: String)

class MailAttachment(val name: String, val bytes: ByteArray, val mime: String)

/**
 * A mailbox over IMAP (read) and SMTP (send) with an app password. Every call opens and closes
 * its own connection; run it off the main thread.
 */
class EmailClient(private val address: String, private val password: String) {
    private val servers = EmailRules.serversFor(address)

    private fun session(): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", servers.imap)
            put("mail.imaps.port", servers.imapPort.toString())
            put("mail.imaps.timeout", "15000")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.smtp.host", servers.smtp)
            put("mail.smtp.port", servers.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.connectiontimeout", "15000")
            if (servers.smtpSsl) put("mail.smtp.ssl.enable", "true") else put("mail.smtp.starttls.enable", "true")
        }
        return Session.getInstance(props)
    }

    private fun <T> inbox(write: Boolean = false, block: (Folder) -> T): T {
        val store = session().getStore("imaps")
        store.connect(servers.imap, address, password)
        try {
            val folder = store.getFolder("INBOX")
            folder.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
            try {
                return block(folder)
            } finally {
                runCatching { folder.close(false) }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    /** Newest first. [query] filters by sender or subject. */
    fun recent(limit: Int = 8, unreadOnly: Boolean = false, query: String = ""): List<MailSummary> = inbox { folder ->
        val uids = folder as UIDFolder
        val messages: Array<Message> = if (unreadOnly) {
            folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
        } else {
            val count = folder.messageCount
            if (count == 0) emptyArray() else folder.getMessages(maxOf(1, count - 60), count)
        }
        val needle = query.trim().lowercase()
        messages.reversed().asSequence()
            .filter { m ->
                needle.isEmpty() || from(m.from).lowercase().contains(needle) || m.subject.orEmpty().lowercase().contains(needle)
            }
            .take(limit)
            .map { m ->
                MailSummary(
                    uids.getUID(m),
                    from(m.from),
                    m.subject.orEmpty(),
                    m.sentDate ?: m.receivedDate,
                    !m.isSet(Flags.Flag.SEEN),
                    text(m).replace(Regex("\\s+"), " ").take(220),
                )
            }.toList()
    }

    fun read(uid: Long): Pair<MailSummary, String>? = inbox { folder ->
        val m = (folder as UIDFolder).getMessageByUID(uid) ?: return@inbox null
        val body = text(m)
        MailSummary(uid, from(m.from), m.subject.orEmpty(), m.sentDate, false, body.take(220)) to body.take(8_000)
    }

    fun send(to: String, subject: String, body: String, attachments: List<MailAttachment> = emptyList()) {
        val session = session()
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(address))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            sentDate = Date()
        }
        if (attachments.isEmpty()) {
            message.setText(body, "UTF-8")
        } else {
            val parts = MimeMultipart()
            parts.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
            attachments.forEach { file ->
                parts.addBodyPart(
                    MimeBodyPart().apply {
                        dataHandler = DataHandler(ByteArrayDataSource(file.bytes, file.mime))
                        fileName = file.name
                    },
                )
            }
            message.setContent(parts)
        }
        val transport = session.getTransport("smtp")
        try {
            transport.connect(servers.smtp, servers.smtpPort, address, password)
            transport.sendMessage(message, message.allRecipients)
        } finally {
            runCatching { transport.close() }
        }
    }

    /** Checks the login on both servers; returns null when fine, else the error. */
    fun test(): String? = runCatching {
        inbox { it.messageCount }
        val transport = session().getTransport("smtp")
        transport.connect(servers.smtp, servers.smtpPort, address, password)
        transport.close()
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    private fun from(addresses: Array<Address>?): String {
        val first = addresses?.firstOrNull() ?: return "desconhecido"
        return (first as? InternetAddress)?.let { it.personal?.let { name -> "$name <${it.address}>" } ?: it.address }
            ?: first.toString()
    }

    private fun text(part: Part, depth: Int = 0): String {
        if (depth > 6) return ""
        return runCatching {
            when {
                part.isMimeType("text/plain") -> part.content as? String ?: ""
                part.isMimeType("text/html") -> EmailRules.htmlToText(part.content as? String ?: "")
                part.isMimeType("multipart/*") -> {
                    val multi = part.content as Multipart
                    val bodies = (0 until multi.count).map { multi.getBodyPart(it) }
                        .filter { Part.ATTACHMENT != it.disposition }
                    val plain = bodies.firstOrNull { it.isMimeType("text/plain") }
                    if (plain != null && part.isMimeType("multipart/alternative")) text(plain, depth + 1)
                    else bodies.joinToString("\n") { text(it, depth + 1) }.trim()
                }
                part.isMimeType("message/rfc822") -> text(part.content as Part, depth + 1)
                else -> ""
            }
        }.getOrDefault("")
    }
}

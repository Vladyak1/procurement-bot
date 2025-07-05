package com.example.procurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.InputStream;
import java.net.URL;
import java.net.HttpURLConnection;

public class TelegramBot extends TelegramLongPollingBot {
    private static final Logger logger = LoggerFactory.getLogger(TelegramBot.class);
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0.00");

    public TelegramBot() {
        initializeCommands();
    }

    private void initializeCommands() {
        if (getBotToken() == null || getBotToken().isEmpty()) {
            logger.error("Bot token is empty, skipping command initialization");
            return;
        }
        List<BotCommand> commands = new ArrayList<>();
        commands.add(new BotCommand("/parse", "Запустить парсинг лотов"));
        commands.add(new BotCommand("/addadmin", "Добавить админа (формат: /addadmin <chatId>)"));
        SetMyCommands setMyCommands = new SetMyCommands();
        setMyCommands.setCommands(commands);
        setMyCommands.setScope(new BotCommandScopeDefault());
        try {
            execute(setMyCommands);
            logger.info("Bot commands initialized");
        } catch (TelegramApiException e) {
            logger.error("Error initializing bot commands: {}", e.getMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return "SevNTO_bot";
    }

    @Override
    public String getBotToken() {
        return Config.getBotToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            String receivedChatId = String.valueOf(chatId);
            List<String> adminIds = Config.getAdminIds().isEmpty() ? new ArrayList<>() : Arrays.asList(Config.getAdminIds().split(","));

            logger.info("Received message '{}' from chat ID: {}", messageText, receivedChatId);

            if (messageText.equals("/parse") && adminIds.contains(receivedChatId)) {
                logger.info("Received /parse command from chat ID: {}", receivedChatId);
                sendMessageWithRetry(chatId, "🚀 Запускаю парсинг...");
                List<Procurement> procurements = new RssParser().parseUntilEnough(5);
                for (Procurement p : procurements) {
                    new LotPageParser().enrichProcurement(p);
                }
                DatabaseManager db = new DatabaseManager();
                List<Procurement> newProcurements = db.getNewProcurements(procurements);
                db.saveProcurements(newProcurements);
                for (Procurement p : newProcurements) {
                    sendProcurementMessage(chatId, p);
                }
                sendMessageWithRetry(chatId, "✅ Парсинг завершён, обработано " + newProcurements.size() + " лотов");
                logger.info("Manual parse completed, {} procurements processed", newProcurements.size());
            } else if (messageText.equals("/parse")) {
                sendMessageWithRetry(chatId, "⛔ У вас нет доступа");
                logger.info("Access denied for /parse command from chat ID: {}", receivedChatId);
            } else if (messageText.startsWith("/addadmin") && adminIds.contains(receivedChatId)) {
                String[] parts = messageText.split(" ");
                if (parts.length != 2) {
                    sendMessageWithRetry(chatId, "⛔ Формат: /addadmin <chatId>");
                    return;
                }
                String newAdminId = parts[1];
                Config.addAdminId(newAdminId);
                sendMessageWithRetry(chatId, "✅ Пользователь " + newAdminId + " добавлен в админы");
                logger.info("Added new admin: {}", newAdminId);
            } else if (messageText.startsWith("/addadmin")) {
                sendMessageWithRetry(chatId, "⛔ У вас нет доступа");
                logger.info("Access denied for /addadmin command from chat ID: {}", receivedChatId);
            } else if (messageText.startsWith("/start lot_") && update.getMessage().getChatId() != null) {
                String procurementNumber = messageText.replace("/start lot_", "");
                DatabaseManager db = new DatabaseManager();
                sendMessageWithRetry(chatId, "Вы выбрали лот №" + procurementNumber + ". Напишите ваш вопрос!");
                logger.info("User started chat for lot: {}", procurementNumber);
            } else if (update.getMessage().getReplyToMessage() != null) {
                handleUserQuestion(update, adminIds);
            } else if (update.getMessage().getForwardDate() != null && adminIds.contains(receivedChatId)) {
                handleForwardedLot(update);
            }
        }
    }

    public void sendProcurementMessage(long chatId, Procurement procurement) {
        // --- Выбор заголовка и подписи к цене ---
        String lotType = "";
        String priceLabel = "";
        if (procurement.getBiddTypeName() != null && procurement.getBiddTypeName().toLowerCase().contains("реализация имущества должников")) {
            lotType = "Реализация имущества должников";
            priceLabel = "Цена за договор";
        } else if (procurement.getContractTypeName() != null && procurement.getContractTypeName().toLowerCase().contains("купли-продажи")) {
            lotType = "Аукцион на право заключения договора купли-продажи недвижимого имущества";
            priceLabel = "Цена за договор";
        } else if (procurement.getContractTypeName() != null && procurement.getContractTypeName().toLowerCase().contains("аренды")) {
            lotType = "Аукцион на право заключения договора аренды на недвижимое имущество";
            if (procurement.getPricePeriod() != null && procurement.getPricePeriod().contains("год")) {
                priceLabel = "Аренда за год";
            } else if (procurement.getPricePeriod() != null && procurement.getPricePeriod().contains("месяц")) {
                priceLabel = "Аренда в месяц";
            } else {
                priceLabel = "Аренда";
            }
        } else {
            lotType = "Аукцион на право заключения договора аренды на недвижимое имущество";
            priceLabel = "Аренда";
        }
        // --- Формируем текст сообщения ---
        StringBuilder message = new StringBuilder();
        // Заголовок всегда жирным (двойное подчёркивание, как для даты)
        String escapedLotType = "__" + escapeMarkdownV2(lotType) + "__";
        message.append(escapedLotType).append("\n\n");
        String escapedTitle = escapeMarkdownV2(procurement.getTitle());
        message.append(escapedTitle).append("\n\n");
        if (procurement.getPrice() != null) {
            String formattedPrice = DECIMAL_FORMAT.format(procurement.getPrice()).replace(".", "\\.");
            message.append("💰").append(priceLabel).append(": ").append(formattedPrice).append(" ₽\n");
        }
        if (procurement.getMonthlyPrice() != null && priceLabel.contains("год")) {
            String formattedMonthlyPrice = DECIMAL_FORMAT.format(procurement.getMonthlyPrice()).replace(".", "\\.");
            message.append("💰Аренда в мес: ").append(formattedMonthlyPrice).append(" ₽\n");
        }
        if (procurement.getDeposit() != null) {
            String formattedDeposit = DECIMAL_FORMAT.format(procurement.getDeposit()).replace(".", "\\.");
            message.append("💰Задаток: ").append(formattedDeposit).append(" ₽\n");
        }
        // --- Организатор торгов ---
        boolean isDebtor = procurement.getBiddTypeName() != null && procurement.getBiddTypeName().toLowerCase().contains("реализация имущества должников");
        if (!isDebtor && procurement.getDepositRecipientName() != null && !procurement.getDepositRecipientName().isEmpty()) {
            String shortOrg = getShortOrgNameFull(procurement.getDepositRecipientName());
            message.append("🏛Организатор торгов: ").append(escapeMarkdownV2(shortOrg)).append("\n");
        }
        // --- Статичный адрес ---
        message.append("🧭г Севастополь\n");
        if (procurement.getContractTerm() != null) {
            String escapedContractTerm = escapeMarkdownV2(procurement.getContractTerm());
            message.append("📅Срок договора (лет): ").append(escapedContractTerm).append("\n");
        }
        if (procurement.getDeadline() != null) {
            String formattedDeadline = procurement.getDeadline();
            try {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(procurement.getDeadline());
                formattedDeadline = odt.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            } catch (Exception ignore) {}
            String escapedDeadline = escapeMarkdownV2(formattedDeadline);
            message.append("⏰Подача до: __").append(escapedDeadline).append("__\n\n");
        }
        message.append("Заинтересовал лот? [Пиши](https://t.me/").append(getBotUsername()).append("?start=lot_").append(procurement.getNumber()).append(") или звони 88007078692");
        // --- Отправка фото и текста как медиа-группа ---
        if (procurement.getImageUrls() != null && !procurement.getImageUrls().isEmpty()) {
            int maxImages = Math.min(4, procurement.getImageUrls().size());
            List<String> urls = procurement.getImageUrls().subList(0, maxImages);
            try {
                if (urls.size() == 1) {
                    String url = urls.get(0);
                    logger.info("IMAGE_URL for procurement {}: {} (downloading)", procurement.getNumber(), url);
                    InputStream in = downloadImage(url);
                    if (in != null) {
                        InputFile inputFile = new InputFile(in, "image.jpg");
                        SendPhoto photo = new SendPhoto();
                        photo.setChatId(chatId);
                        photo.setPhoto(inputFile);
                        photo.setCaption(message.toString());
                        photo.setParseMode("MarkdownV2");
                        executeWithRetry(photo);
                        in.close();
                        logger.info("Sent 1 image for procurement: {} (downloaded)", procurement.getNumber());
                    } else {
                        logger.warn("Failed to download image for procurement: {}", procurement.getNumber());
                        sendTextFallback(chatId, message.toString(), procurement.getNumber());
                    }
                } else {
                    List<InputMedia> media = new ArrayList<>();
                    List<InputStream> streams = new ArrayList<>();
                    for (int i = 0; i < urls.size(); i++) {
                        String url = urls.get(i);
                        logger.info("IMAGE_URL for procurement {}: {} (downloading)", procurement.getNumber(), url);
                        InputStream in = downloadImage(url);
                        if (in != null) {
                            InputMediaPhoto photo = new InputMediaPhoto();
                            photo.setMedia(in, "image" + i + ".jpg");
                            if (i == 0) {
                                photo.setCaption(message.toString());
                                photo.setParseMode("MarkdownV2");
                            }
                            media.add(photo);
                            streams.add(in);
                        } else {
                            logger.warn("Failed to download image {} for procurement: {}", i, procurement.getNumber());
                        }
                    }
                    if (!media.isEmpty()) {
                        SendMediaGroup mediaGroup = new SendMediaGroup();
                        mediaGroup.setChatId(chatId);
                        mediaGroup.setMedias(media);
                        executeWithRetry(mediaGroup);
                        logger.info("Sent {} images for procurement: {} (downloaded)", media.size(), procurement.getNumber());
                    } else {
                        logger.warn("No images could be downloaded for procurement: {}", procurement.getNumber());
                        sendTextFallback(chatId, message.toString(), procurement.getNumber());
                    }
                    // Закрываем все потоки
                    for (InputStream s : streams) try { s.close(); } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                logger.error("Failed to download/send images for procurement {}: {}", procurement.getNumber(), e.getMessage());
                sendTextFallback(chatId, message.toString(), procurement.getNumber());
            }
        } else {
            sendTextFallback(chatId, message.toString(), procurement.getNumber());
        }
    }

    private void handleUserQuestion(Update update, List<String> adminIds) {
        long chatId = update.getMessage().getChatId();
        int replyToMessageId = update.getMessage().getReplyToMessage().getMessageId();
        String userMessage = update.getMessage().getText();
        DatabaseManager db = new DatabaseManager();
        String procurementNumber = db.getProcurementNumberByMessageId(replyToMessageId, chatId);

        if (procurementNumber != null) {
            String forwardMessage = String.format("Вопрос от пользователя %s по лоту %s:\n%s", chatId, procurementNumber, userMessage);
            for (String adminId : adminIds) {
                sendMessageWithRetry(Long.parseLong(adminId), forwardMessage);
            }
            sendMessageWithRetry(chatId, "Ваш вопрос отправлен админу!");
            logger.info("Forwarded user question for procurement {} from chat ID: {}", procurementNumber, chatId);
        }
    }

    private void handleForwardedLot(Update update) {
        long chatId = update.getMessage().getChatId();
        int forwardedMessageId = update.getMessage().getMessageId();
        DatabaseManager db = new DatabaseManager();
        String procurementNumber = db.getProcurementNumberByMessageId(forwardedMessageId, chatId);

        if (procurementNumber != null) {
            String lotUrl = "https://torgi.gov.ru/new/public/lots/lot/" + procurementNumber + "/(lotInfo:info)?fromRec=false";
            sendMessageWithRetry(chatId, "Ссылка на лот: " + lotUrl);
            logger.info("Sent lot URL {} for forwarded message from chat ID: {}", lotUrl, chatId);
        } else {
            sendMessageWithRetry(chatId, "⛔ Лот не найден");
            logger.warn("No procurement found for forwarded message ID {} from chat ID: {}", forwardedMessageId, chatId);
        }
    }

    public void sendMessageWithRetry(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        String escapedText = escapeMarkdownV2(text);
        message.setText(escapedText);
        message.setParseMode("MarkdownV2");
        executeWithRetry(message);
    }

    private Integer executeWithRetry(Object method) {
        try {
            if (method instanceof SendMessage) {
                return execute((SendMessage) method).getMessageId();
            } else if (method instanceof SendMediaGroup) {
                execute((SendMediaGroup) method);
                return null;
            } else if (method instanceof SendPhoto) {
                return execute((SendPhoto) method).getMessageId();
            }
        } catch (TelegramApiException e) {
            logger.error("Failed to execute method: {}", e.getMessage());
        }
        return null;
    }

    private String escapeMarkdownV2(String text) {
        if (text == null) return "";
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace(">", "\\>")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("=", "\\=")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace(".", "\\.")
                .replace("!", "\\!");
    }

    // Сокращение названия организатора по первым буквам каждого слова (кроме служебных)
    private String getShortOrgName(String fullName) {
        String[] serviceWords = {"по", "и", "в", "на", "с", "к", "от", "до", "за", "из", "у", "о", "об", "а", "но", "для", "при", "без", "над", "под", "про", "через", "после", "между", "надо", "через", "либо", "или", "то", "же", "бы", "же", "да", "ли", "быть", "этот", "тот", "такой", "так", "же", "как", "что", "чтобы", "который", "свой", "наш", "ваш", "их", "её", "его", "её", "их"};
        java.util.Set<String> serviceSet = new java.util.HashSet<>();
        for (String w : serviceWords) serviceSet.add(w.toLowerCase());
        StringBuilder sb = new StringBuilder();
        String[] words = fullName.replaceAll("[\"«»]", "").split("[\s,]+");
        for (String word : words) {
            if (word.isEmpty()) continue;
            String lower = word.toLowerCase();
            if (serviceSet.contains(lower)) continue;
            if (word.length() > 0 && Character.isLetter(word.charAt(0))) {
                sb.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        // Спец. случаи для ГУП, МУП, ГКУ, ДИЗО и т.д.
        if (fullName.toUpperCase().contains("ГУП")) return "ГУП \"" + sb.toString() + "\"";
        if (fullName.toUpperCase().contains("МУП")) return "МУП \"" + sb.toString() + "\"";
        if (fullName.toUpperCase().contains("ГКУ")) return "ГКУ \"" + sb.toString() + "\"";
        if (fullName.toUpperCase().contains("ДЕПАРТАМЕНТ")) return sb.toString();
        return sb.toString();
    }

    // Новый метод для сокращения с кавычками и пробелом
    private String getShortOrgNameFull(String fullName) {
        String upper = fullName.toUpperCase();
        if (upper.contains("ГУП")) {
            String core = extractCoreName(fullName);
            return "ГУП \"" + core + "\"";
        }
        if (upper.contains("МУП")) {
            String core = extractCoreName(fullName);
            return "МУП \"" + core + "\"";
        }
        if (upper.contains("ГКУ")) {
            String core = extractCoreName(fullName);
            return "ГКУ \"" + core + "\"";
        }
        if (upper.contains("ДИЗО")) {
            String core = extractCoreName(fullName);
            return "ДИЗО \"" + core + "\"";
        }
        // Если не спец. случай — просто сокращаем по первым буквам, но без кавычек
        return getShortOrgName(fullName);
    }

    // Вспомогательный метод для извлечения "ядра" названия
    private String extractCoreName(String fullName) {
        String[] words = fullName.replaceAll("[\"«»]", "").split("[\s,]+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (word.length() > 0 && Character.isLetter(word.charAt(0)) && word.equals(word.toUpperCase())) {
                sb.append(word);
                break;
            }
        }
        return sb.length() > 0 ? sb.toString() : fullName;
    }

    private InputStream downloadImage(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            return conn.getInputStream();
        } catch (Exception e) {
            logger.warn("Failed to download image from {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    private void sendTextFallback(long chatId, String text, String procurementNumber) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(text);
        sendMessage.setParseMode("MarkdownV2");
        executeWithRetry(sendMessage);
        logger.warn("Fallback: sent only text for procurement: {}", procurementNumber);
    }
}
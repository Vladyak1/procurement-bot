package com.example.procurement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TelegramBot extends TelegramLongPollingBot {
    private static final Logger logger = LoggerFactory.getLogger(TelegramBot.class);
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0.00");
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

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
        StringBuilder message = new StringBuilder();
        String lotType = "Аукцион на право заключения договора аренды на недвижимое имущество";
        message.append("**").append(escapeMarkdownV2(lotType)).append("**\n\n");
        String escapedTitle = escapeMarkdownV2(procurement.getTitle());
        message.append(escapedTitle).append("\n\n");
        if (procurement.getPrice() != null) {
            String formattedPrice = DECIMAL_FORMAT.format(procurement.getPrice()).replace(".", "\\.");
            message.append("💰Аренда за год: ").append(formattedPrice).append(" ₽\n");
        }
        if (procurement.getMonthlyPrice() != null) {
            String formattedMonthlyPrice = DECIMAL_FORMAT.format(procurement.getMonthlyPrice()).replace(".", "\\.");
            message.append("💰Аренда в мес: ").append(formattedMonthlyPrice).append(" ₽\n");
        }
        if (procurement.getDeposit() != null) {
            String formattedDeposit = DECIMAL_FORMAT.format(procurement.getDeposit()).replace(".", "\\.");
            message.append("💰Задаток: ").append(formattedDeposit).append(" ₽\n");
        }
        message.append("🧭г Севастополь\n");
        if (procurement.getContractTerm() != null) {
            String escapedContractTerm = escapeMarkdownV2(procurement.getContractTerm());
            message.append("📅Срок договора (лет): ").append(escapedContractTerm).append("\n");
        }
        if (procurement.getDeadline() != null) {
            String escapedDeadline = escapeMarkdownV2(procurement.getDeadline());
            message.append("⏰Подача до: __").append(escapedDeadline).append("__\n\n");
        }
        message.append("Заинтересовал лот? [Пиши](https://t.me/").append(getBotUsername()).append("?start=lot_").append(procurement.getNumber()).append(") или звони 88007078692");

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(message.toString());
        sendMessage.setParseMode("MarkdownV2");

        Integer messageId = executeWithRetry(sendMessage);
        if (messageId != null) {
            DatabaseManager db = new DatabaseManager();
            db.saveMessageId(procurement.getNumber(), messageId, chatId);
            db.markAsSent(procurement.getNumber());
            logger.info("Sent message for procurement: {}, messageId: {}", procurement.getNumber(), messageId);

            if (procurement.getImageUrls() != null && !procurement.getImageUrls().isEmpty()) {
                List<InputMedia> media = new ArrayList<>();
                for (String url : procurement.getImageUrls()) {
                    InputMediaPhoto photo = new InputMediaPhoto();
                    photo.setMedia(url);
                    media.add(photo);
                    logger.debug("Preparing to send image: {}", url);
                }
                SendMediaGroup mediaGroup = new SendMediaGroup();
                mediaGroup.setChatId(chatId);
                mediaGroup.setMedias(media);
                executeWithRetry(mediaGroup);
                logger.info("Sent {} images for procurement: {}", media.size(), procurement.getNumber());
            } else {
                logger.warn("No images found for procurement: {}", procurement.getNumber());
            }
        } else {
            logger.error("Failed to send message for procurement: {}", procurement.getNumber());
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
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                if (method instanceof SendMessage) {
                    return execute((SendMessage) method).getMessageId();
                } else if (method instanceof SendMediaGroup) {
                    execute((SendMediaGroup) method);
                    return null;
                }
            } catch (TelegramApiException e) {
                attempt++;
                if (attempt == MAX_RETRIES) {
                    logger.error("Failed to execute method after {} attempts: {}", MAX_RETRIES, e.getMessage());
                    return null;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    logger.error("Retry interrupted: {}", ie.getMessage());
                    Thread.currentThread().interrupt();
                    return null;
                }
                logger.warn("Retrying method execution, attempt {}/{}", attempt + 1, MAX_RETRIES);
            }
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
}
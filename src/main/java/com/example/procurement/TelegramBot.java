package com.example.procurement;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.InputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class TelegramBot extends TelegramLongPollingBot {
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0.00");
    private static final ConcurrentHashMap<Long, String> userLotMap = new ConcurrentHashMap<>();
    // adminId -> ReplyContext (userId, userChatId, lotId, вопрос)
    private static final Map<Long, ReplyContext> adminReplyMap = new ConcurrentHashMap<>();
    // adminId -> true если ожидается пересылка лота для удаления
    private static final Map<Long, Boolean> adminDeleteLotMap = new ConcurrentHashMap<>();
    // questionId -> QuestionContext (userId, userChatId, lotId, вопрос)
    private static final Map<String, QuestionContext> questionMap = new ConcurrentHashMap<>();

    private static class ReplyContext {
        public final Long userId;
        public final Long userChatId;
        public final String lotId;
        public final String questionText;
        public ReplyContext(Long userId, Long userChatId, String lotId, String questionText) {
            this.userId = userId;
            this.userChatId = userChatId;
            this.lotId = lotId;
            this.questionText = questionText;
        }
    }

    private static class QuestionContext {
        public final Long userId;
        public final Long userChatId;
        public final String lotId;
        public final String questionText;
        public QuestionContext(Long userId, Long userChatId, String lotId, String questionText) {
            this.userId = userId;
            this.userChatId = userChatId;
            this.lotId = lotId;
            this.questionText = questionText;
        }
    }

    public TelegramBot() {
        initializeCommands();
    }

    private void initializeCommands() {
        if (getBotToken() == null || getBotToken().isEmpty()) {
            log.error("Bot token is empty, skipping command initialization");
            return;
        }
        List<BotCommand> adminCommands = new ArrayList<>();
        adminCommands.add(new BotCommand("/parse", "Запустить парсинг лотов"));
        adminCommands.add(new BotCommand("/fullparse", "Принудительный полный парсинг и публикация в чат парсинга"));
        adminCommands.add(new BotCommand("/addadmin", "Добавить админа (формат: /addadmin <chatId>)"));
        adminCommands.add(new BotCommand("/removeadmin", "Удалить админа (формат: /removeadmin <chatId>)"));
        try {
            SetMyCommands setAdminCommands = new SetMyCommands();
            setAdminCommands.setCommands(adminCommands);
            setAdminCommands.setScope(new BotCommandScopeChat(String.valueOf(Config.getAdminGroupId())));
            execute(setAdminCommands);
            log.info("Admin commands set for admin group {}", Config.getAdminGroupId());

            SetMyCommands clearDefault = new SetMyCommands();
            clearDefault.setCommands(new ArrayList<>());
            clearDefault.setScope(new BotCommandScopeDefault());
            execute(clearDefault);
            log.info("Cleared commands for default scope");

            SetMyCommands clearParseGroup = new SetMyCommands();
            clearParseGroup.setCommands(new ArrayList<>());
            clearParseGroup.setScope(new BotCommandScopeChat(String.valueOf(Config.getParseGroupId())));
            execute(clearParseGroup);
            log.info("Cleared commands for parse group {}", Config.getParseGroupId());
        } catch (TelegramApiException e) {
            log.error("Error initializing bot commands: {}", e.getMessage());
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
        if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            Long userId = update.getCallbackQuery().getFrom().getId();
            String username = update.getCallbackQuery().getFrom().getUserName();
            if ("cancel_admin_reply".equals(callbackData)) {
                adminReplyMap.remove(userId);
                sendMessageWithRetry(chatId, "Ответ на вопрос отменён. Вы можете вернуться к нему позже.");
                return;
            }
            if ("cancel_question".equals(callbackData)) {
                userLotMap.remove(userId);
                sendMessageWithRetry(chatId, "Диалог отменён.");
                log.info("[BOT] Пользователь отменил вопрос: userId={}, username={}", userId, username);
                return;
            }
            if ("cancel_delete_lot".equals(callbackData)) {
                adminDeleteLotMap.remove(userId);
                sendMessageWithRetry(chatId, "Удаление лота отменено.");
                return;
            }
            if (callbackData.startsWith("reply_to_user_question|")) {
                String[] parts = callbackData.split("\\|", 2);
                if (parts.length == 2) {
                    String questionId = parts[1];
                    QuestionContext ctx = questionMap.get(questionId);
                    if (ctx != null) {
                        adminReplyMap.put(userId, new ReplyContext(ctx.userId, ctx.userChatId, ctx.lotId, ctx.questionText));
                        // Уведомление в чат админов с кнопкой "Отмена"
                        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                        List<InlineKeyboardButton> row = new ArrayList<>();
                        InlineKeyboardButton cancelButton = new InlineKeyboardButton();
                        cancelButton.setText("Отмена");
                        cancelButton.setCallbackData("cancel_admin_reply");
                        row.add(cancelButton);
                        rows.add(row);
                        markup.setKeyboard(rows);
                        SendMessage msg = new SendMessage();
                        msg.setChatId(chatId);
                        msg.setText("Вы отвечаете на вопрос пользователя по лоту " + ctx.lotId + ". После отправки сообщения оно будет переслано пользователю.\n\nВопрос: " + ctx.questionText);
                        msg.setReplyMarkup(markup);
                        executeWithRetry(msg);
                    }
                }
                return;
            }
            return;
        }
        if (update.hasMessage()) {
            long chatId = update.getMessage().getChatId();
            int messageId = update.getMessage().getMessageId();
            List<String> adminIds = Config.getAdminIds().isEmpty() ? new ArrayList<>() : Arrays.asList(Config.getAdminIds().split(","));
            String username = update.getMessage().getFrom() != null ? update.getMessage().getFrom().getUserName() : null;
            Long userId = update.getMessage().getFrom() != null ? update.getMessage().getFrom().getId() : null;
            String userIdStr = userId != null ? String.valueOf(userId) : null;
            String mainText = update.getMessage().getText() != null ? update.getMessage().getText() : update.getMessage().getCaption();

            // Подробное логирование всех сообщений в чате админов
            if (chatId == Config.getAdminGroupId()) {
                if (update.getMessage().getForwardFrom() != null
                    && update.getMessage().getForwardFrom().getUserName() != null
                    && update.getMessage().getForwardFrom().getUserName().equals("SevNTO_bot")
                    && mainText != null && !mainText.isEmpty()) {
                    if (userId != null && adminDeleteLotMap.getOrDefault(userId, false)) {
                        // Логика удаления лота
                        int deleted = 0;
                        String procurementNumber = null;
                        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(Config.getDbUrl().startsWith("jdbc:") ? Config.getDbUrl() : "jdbc:sqlite:" + Config.getDbUrl())) {
                            java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT number, title FROM procurements");
                            java.sql.ResultSet rs = stmt.executeQuery();
                            String mainTextNorm = mainText.replaceAll("\\s+", " ").toLowerCase();
                            while (rs.next()) {
                                String dbTitle = rs.getString("title");
                                String dbTitleNorm = dbTitle != null ? dbTitle.replaceAll("\\s+", " ").toLowerCase() : "";
                                if (dbTitle != null && mainTextNorm.contains(dbTitleNorm)) {
                                    procurementNumber = rs.getString("number");
                                    break;
                                }
                            }
                            rs.close();
                            stmt.close();
                            if (procurementNumber != null) {
                                java.sql.PreparedStatement delStmt = conn.prepareStatement("DELETE FROM procurements WHERE number = ?");
                                delStmt.setString(1, procurementNumber);
                                deleted = delStmt.executeUpdate();
                                delStmt.close();
                            }
                        } catch (Exception e) {
                            sendMessageWithRetry(chatId, "Ошибка при удалении лота: " + e.getMessage());
                        }
                        if (deleted > 0) {
                            sendMessageWithRetry(chatId, "Лот успешно удалён из базы данных.");
                            log.info("[BOT] Удаление лота: userId={}, lotId={}", userId, procurementNumber);
                        } else {
                            sendMessageWithRetry(chatId, "Лот не найден в базе данных.");
                            log.info("[BOT] Лот не найден для удаления: userId={}", userId);
                        }
                        adminDeleteLotMap.remove(userId);
                        return;
                    } else {
                        log.info("[BOT] Поиск ссылки на лот: userId={}", userId);
                        handleForwardedLot(update);
                    }
                }
            }

            // Если mainText всё равно null — игнорируем сообщение
            if (mainText == null) {
                log.info("[DEBUG] Message ignored: mainText is null");
                return;
            }

            // Если это чат админов и у админа есть активный ответ — пересылаем ответ пользователю
            if (chatId == Config.getAdminGroupId() && userId != null && adminReplyMap.containsKey(userId)) {
                ReplyContext ctx = adminReplyMap.get(userId);
                // Пересылаем ответ пользователю
                String answer = mainText; // Используем текст сообщения, которое пришло в ответ
                StringBuilder msgBuilder = new StringBuilder();
                msgBuilder.append("Ответ на ваш вопрос по лоту ").append(ctx.lotId).append("\n\n");
                msgBuilder.append('"').append(ctx.questionText).append('"').append("\n\n");
                msgBuilder.append("Ответ администратора: ").append(answer);
                sendMessageWithRetry(ctx.userChatId, msgBuilder.toString());
                sendMessageWithRetry(chatId, "Ответ отправлен пользователю.");
                adminReplyMap.remove(userId);
                return;
            }

            // /deletelot только для админов
            if (chatId == Config.getAdminGroupId() && "/deletelot".equalsIgnoreCase(mainText.trim()) && userId != null && adminIds.contains(userIdStr)) {
                adminDeleteLotMap.put(userId, true);
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                List<InlineKeyboardButton> row = new ArrayList<>();
                InlineKeyboardButton cancelButton = new InlineKeyboardButton();
                cancelButton.setText("Отмена");
                cancelButton.setCallbackData("cancel_delete_lot");
                row.add(cancelButton);
                rows.add(row);
                markup.setKeyboard(rows);
                SendMessage msg = new SendMessage();
                msg.setChatId(chatId);
                msg.setText("Пришлите лот для удаления из базы данных");
                msg.setReplyMarkup(markup);
                executeWithRetry(msg);
                return;
            }

            String msgTextLower = mainText.toLowerCase();
            boolean isParseCmd = msgTextLower.equals("/parse") || msgTextLower.equals("/parse@sevnto_bot");
            boolean isFullParseCmd = msgTextLower.equals("/fullparse") || msgTextLower.equals("/fullparse@sevnto_bot");
            boolean isAddAdminCmd = msgTextLower.startsWith("/addadmin") || msgTextLower.startsWith("/addadmin@sevnto_bot");
            boolean isRemoveAdminCmd = msgTextLower.startsWith("/removeadmin") || msgTextLower.startsWith("/removeadmin@sevnto_bot");
            boolean isCommand = isParseCmd || isFullParseCmd || isAddAdminCmd || isRemoveAdminCmd;

            if (mainText.startsWith("/start lot_") && userId != null) {
                String lotId = mainText.replace("/start lot_", "");
                userLotMap.put(userId, lotId);
                DatabaseManager db = AppContext.getDatabaseManager();
                Procurement procurement = db.getProcurementByNumber(lotId);
                String msg = "Вы решили задать вопрос по следующему лоту:\n\n";
                msg += procurement != null ? procurement.getTitle().replace("(", "(").replace(")", ")") : "Описание не найдено"; // Simple escaping for HTML
                msg += "\n\nНапишите ваш вопрос!";

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                List<InlineKeyboardButton> row = new ArrayList<>();
                InlineKeyboardButton cancelButton = new InlineKeyboardButton();
                cancelButton.setText("Отмена");
                cancelButton.setCallbackData("cancel_question");
                row.add(cancelButton);
                rows.add(row);
                markup.setKeyboard(rows);

                SendMessage sendMessage = new SendMessage();
                sendMessage.setChatId(chatId);
                sendMessage.setText(msg);
                sendMessage.setParseMode("HTML");
                sendMessage.setReplyMarkup(markup);
                executeWithRetry(sendMessage);
                log.info("User {} started chat for lot: {}", userId, lotId);
                return;
            }
            if (mainText != null && mainText.startsWith("/start cancel") && userId != null) {
                userLotMap.remove(userId);
                sendMessageWithRetry(chatId, "Диалог отменён.");
                log.info("[BOT] Пользователь отменил вопрос (через команду): userId={}, username={}", userId, username);
                return;
            }
            if (userId != null && userLotMap.containsKey(userId) && chatId == userId) { // Только в private chat
                String lotId = userLotMap.get(userId);
                DatabaseManager db = AppContext.getDatabaseManager();
                Procurement procurement = db.getProcurementByNumber(lotId);
                String lotTitle = procurement != null ? procurement.getTitle() : "Описание лота не найдено";
                sendUserQuestionToAdmins(userId, username, lotId, lotTitle, mainText);
                sendMessageWithRetry(chatId, "Ваш вопрос отправлен админу!");
                userLotMap.remove(userId);
                log.info("Forwarded user question for lot {} from user {}", lotId, userId);
                return;
            }
            if (chatId == Config.getAdminGroupId()) {
                if (isParseCmd) {
                    handleParseCommand(chatId, messageId, userIdStr, adminIds, 2);
                    return;
                } else if (isFullParseCmd) {
                    handleFullParseCommand(chatId, messageId, userIdStr, adminIds);
                    return;
                } else if (isAddAdminCmd) {
                    handleAddAdminCommand(chatId, mainText, userIdStr, adminIds);
                    return;
                } else if (isRemoveAdminCmd) {
                    handleRemoveAdminCommand(chatId, mainText, userIdStr, adminIds);
                    return;
                }
            }
            if (isCommand) {
                try {
                    deleteMessage(chatId, messageId);
                    log.info("Deleted command message {} in non-admin chat {}", messageId, chatId);
                } catch (Exception e) {
                    log.info("Could not delete command in chat {}: {}", chatId, e.getMessage());
                }
                return;
            }
            if (update.getMessage().getReplyToMessage() != null) {
                handleUserQuestion(update, adminIds);
            }
        }
    }

    public void sendProcurementMessage(long chatId, Procurement procurement) {
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
        StringBuilder message = new StringBuilder();
        message.append("<b>").append(lotType).append("</b>\n\n");
        message.append(procurement.getTitle()).append("\n\n");
        if (procurement.getPrice() != null) {
            String formattedPrice = DECIMAL_FORMAT.format(procurement.getPrice());
            message.append("💰").append(priceLabel).append(": ").append(formattedPrice).append(" ₽\n");
        }
        if (procurement.getMonthlyPrice() != null && priceLabel.contains("год")) {
            String formattedMonthlyPrice = DECIMAL_FORMAT.format(procurement.getMonthlyPrice());
            message.append("💰Аренда в мес: ").append(formattedMonthlyPrice).append(" ₽\n");
        }
        if (procurement.getDeposit() != null) {
            String formattedDeposit = DECIMAL_FORMAT.format(procurement.getDeposit());
            message.append("💰Задаток: ").append(formattedDeposit).append(" ₽\n");
        }
        boolean isDebtor = procurement.getBiddTypeName() != null && procurement.getBiddTypeName().toLowerCase().contains("реализация имущества должников");
        if (!isDebtor && procurement.getDepositRecipientName() != null && !procurement.getDepositRecipientName().isEmpty()) {
            String shortOrg = getShortOrgNameFull(procurement.getDepositRecipientName());
            message.append("🏛Организатор торгов: ").append(shortOrg).append("\n");
        }
        message.append("🧭г Севастополь\n");
        if (procurement.getContractTerm() != null) {
            message.append("📅Срок договора (лет): ").append(procurement.getContractTerm()).append("\n");
        }
        if (procurement.getDeadline() != null) {
            String formattedDeadline = procurement.getDeadline();
            try {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(procurement.getDeadline());
                formattedDeadline = odt.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            } catch (Exception ignore) {}
            message.append("⏰Подача до: <u>").append(formattedDeadline).append("</u>\n\n");
        }
        message.append("Заинтересовал лот? <a href=\"https://t.me/").append(getBotUsername()).append("?start=lot_").append(procurement.getNumber()).append("\">Пиши</a> или звони 88007078692");

        boolean sent = false;
        Integer sentMessageId = null;
        if (procurement.getImageUrls() != null && !procurement.getImageUrls().isEmpty()) {
            int maxImages = Math.min(4, procurement.getImageUrls().size());
            List<String> urls = procurement.getImageUrls().subList(0, maxImages);
            try {
                if (urls.size() == 1) {
                    String url = urls.get(0);
                    log.info("IMAGE_URL for procurement {}: {} (downloading)", procurement.getNumber(), url);
                    InputStream in = downloadImage(url);
                    if (in != null) {
                        InputFile inputFile = new InputFile(in, "image.jpg");
                        SendPhoto photo = new SendPhoto();
                        photo.setChatId(chatId);
                        photo.setPhoto(inputFile);
                        photo.setCaption(message.toString());
                        photo.setParseMode("HTML");
                        sentMessageId = executeWithRetry(photo);
                        in.close();
                        log.info("Sent 1 image for procurement: {} (downloaded)", procurement.getNumber());
                        sent = true;
                    } else {
                        log.warn("Failed to download image for procurement: {}", procurement.getNumber());
                        sentMessageId = executeWithRetry(new SendMessage(String.valueOf(chatId), message.toString()));
                    }
                } else {
                    List<InputMedia> media = new ArrayList<>();
                    List<InputStream> streams = new ArrayList<>();
                    for (int i = 0; i < urls.size(); i++) {
                        String url = urls.get(i);
                        log.info("IMAGE_URL for procurement {}: {} (downloading)", procurement.getNumber(), url);
                        InputStream in = downloadImage(url);
                        try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        if (in != null) {
                            InputMediaPhoto photo = new InputMediaPhoto();
                            photo.setMedia(in, "image" + i + ".jpg");
                            if (i == 0) {
                                photo.setCaption(message.toString());
                                photo.setParseMode("HTML");
                            }
                            media.add(photo);
                            streams.add(in);
                        } else {
                            log.warn("Failed to download image {} for procurement: {}", i, procurement.getNumber());
                        }
                    }
                    if (!media.isEmpty()) {
                        SendMediaGroup mediaGroup = new SendMediaGroup();
                        mediaGroup.setChatId(chatId);
                        mediaGroup.setMedias(media);
                        executeWithRetry(mediaGroup);
                        log.info("Sent {} images for procurement: {} (downloaded)", media.size(), procurement.getNumber());
                    } else {
                        log.warn("No images could be downloaded for procurement: {}", procurement.getNumber());
                        sentMessageId = executeWithRetry(new SendMessage(String.valueOf(chatId), message.toString()));
                    }
                    for (InputStream s : streams) try { s.close(); } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                log.error("Failed to download/send images for procurement {}: {}", procurement.getNumber(), e.getMessage());
                sentMessageId = executeWithRetry(new SendMessage(String.valueOf(chatId), message.toString()));
            }
        } else {
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(chatId);
            sendMessage.setText(message.toString());
            sendMessage.setParseMode("HTML");
            sentMessageId = executeWithRetry(sendMessage);
            sent = true;
        }
        // Сохраняем связь messageId <-> номер лота
        if (sentMessageId != null) {
            DatabaseManager db = AppContext.getDatabaseManager();
            db.saveMessageId(procurement.getNumber(), sentMessageId, chatId);
        }
    }

    private void sendUserQuestionToAdmins(Long userId, String username, String lotId, String lotTitle, String userMessage) {
        String userLink = username != null ? "<a href=\"tg://user?id=" + userId + "\">@" + username + "</a>" : "tg://user?id=" + userId;
        StringBuilder msgBuilder = new StringBuilder();
        msgBuilder.append("Вопрос от пользователя ").append(userLink).append("\n\n");
        msgBuilder.append(lotTitle).append("\n\n");
        msgBuilder.append('"').append(userMessage).append('"');
        String questionId = UUID.randomUUID().toString();
        questionMap.put(questionId, new QuestionContext(userId, userId, lotId, userMessage));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton replyButton = new InlineKeyboardButton();
        replyButton.setText("Ответить");
        replyButton.setCallbackData("reply_to_user_question|" + questionId);
        row.add(replyButton);
        rows.add(row);
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage();
        msg.setChatId(Config.getAdminGroupId());
        msg.setText(msgBuilder.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        executeWithRetry(msg);
    }

    private void handleUserQuestion(Update update, List<String> adminIds) {
        long chatId = update.getMessage().getChatId();
        int replyToMessageId = update.getMessage().getReplyToMessage().getMessageId();
        String userMessage = update.getMessage().getText();
        DatabaseManager db = AppContext.getDatabaseManager();
        String procurementNumber = db.getProcurementNumberByMessageId(replyToMessageId, chatId);
        if (procurementNumber != null) {
            Procurement procurement = db.getProcurementByNumber(procurementNumber);
            String lotTitle = procurement != null ? procurement.getTitle() : "Описание лота не найдено";
            Long userId = update.getMessage().getFrom() != null ? update.getMessage().getFrom().getId() : null;
            String username = update.getMessage().getFrom() != null ? update.getMessage().getFrom().getUserName() : null;
            sendUserQuestionToAdmins(userId, username, procurementNumber, lotTitle, userMessage);
            sendMessageWithRetry(chatId, "Ваш вопрос отправлен админу!");
            log.info("Forwarded user question for procurement {} from chat ID: {}", procurementNumber, chatId);
        }
    }

    private void handleForwardedLot(Update update) {
        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();
        String caption = update.getMessage().getCaption();
        String mainText = text != null ? text : caption;
        String procurementNumber = null;
        if (mainText != null && !mainText.isEmpty()) {
            String mainTextNorm = mainText.replaceAll("\\s+", " ").toLowerCase();
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(Config.getDbUrl().startsWith("jdbc:") ? Config.getDbUrl() : "jdbc:sqlite:" + Config.getDbUrl())) {
                java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT number, title FROM procurements");
                java.sql.ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String dbTitle = rs.getString("title");
                    String dbTitleNorm = dbTitle != null ? dbTitle.replaceAll("\\s+", " ").toLowerCase() : "";
                    if (dbTitle != null && mainTextNorm.contains(dbTitleNorm)) {
                        procurementNumber = rs.getString("number");
                        break;
                    }
                }
                rs.close();
                stmt.close();
            } catch (Exception e) {
                sendMessageWithRetry(chatId, "⛔ Ошибка поиска лота: " + e.getMessage());
            }
        }
        Long userId = update.getMessage().getFrom() != null ? update.getMessage().getFrom().getId() : null;
        if (procurementNumber != null) {
            String lotUrl = "https://torgi.gov.ru/new/public/lots/lot/" + procurementNumber + "/(lotInfo:info)?fromRec=false";
            sendMessageWithRetry(chatId, "Ссылка на лот: " + lotUrl);
            log.info("[BOT] Ссылка на лот отправлена: userId={}, lotId={}", userId, procurementNumber);
        } else {
            sendMessageWithRetry(chatId, "⛔ Лот не найден");
            log.info("[BOT] Лот не найден для ссылки: userId={}", userId);
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
            log.error("Failed to execute method: {}", e.getMessage());
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
        if (fullName.toUpperCase().contains("ГУП")) return "ГУП \"" + sb.toString() + "\"";
        if (fullName.toUpperCase().contains("МУП")) return "МУП \"" + sb.toString() + "\"";
        if (fullName.toUpperCase().contains("ГКУ")) return "ГКУ \"" + sb.toString() + "\"";
        if (fullName.toUpperCase().contains("ДЕПАРТАМЕНТ")) return sb.toString();
        return sb.toString();
    }

    private String getShortOrgNameFull(String fullName) {
        if (fullName == null) return "";
        int quoteStart = fullName.indexOf('"');
        int quoteEnd = fullName.indexOf('"', quoteStart + 1);
        if (quoteStart != -1 && quoteEnd != -1 && quoteEnd > quoteStart) {
            String before = fullName.substring(0, quoteStart).trim();
            String inQuotes = fullName.substring(quoteStart + 1, quoteEnd).trim();
            StringBuilder abbrBefore = new StringBuilder();
            for (String word : before.split("[\s,]+")) {
                if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
                    abbrBefore.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            StringBuilder abbrQuotes = new StringBuilder();
            for (String word : inQuotes.split("[\s,]+")) {
                if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
                    abbrQuotes.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            return abbrBefore + " \"" + abbrQuotes + "\"";
        } else {
            StringBuilder abbr = new StringBuilder();
            for (String word : fullName.split("[\s,]+")) {
                if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
                    abbr.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            return abbr.toString();
        }
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
            log.warn("Failed to download image from {}: {}", imageUrl, e.getMessage());
            return null;
        }
    }

    private void sendTextFallback(long chatId, String text, String procurementNumber) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(text);
        sendMessage.setParseMode("MarkdownV2");
        executeWithRetry(sendMessage);
        log.warn("Fallback: sent only text for procurement: {}", procurementNumber);
    }

    private void deleteMessage(long chatId, int messageId) {
        try {
            DeleteMessage deleteMessage = new DeleteMessage();
            deleteMessage.setChatId(chatId);
            deleteMessage.setMessageId(messageId);
            execute(deleteMessage);
            log.info("Message {} deleted from chat {}", messageId, chatId);
        } catch (TelegramApiException e) {
            log.info("Could not delete message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }

    private int parseAndPublishLots(int maxCount, long chatId, boolean notifyAdminOnNoMatch) {
        ParserService parserService = AppContext.getParserService();
        List<Procurement> procurements = parserService.parseAndEnrich(Integer.MAX_VALUE, notifyAdminOnNoMatch);
        DatabaseManager db = AppContext.getDatabaseManager();
        db.saveProcurements(procurements);
        List<Procurement> newProcurements = db.getNewProcurements(procurements);
        int published = 0;
        for (Procurement p : newProcurements) {
            if (published >= maxCount) break;
            log.info("Публикация лота {}...", p.getNumber());
            sendProcurementMessage(chatId, p);
            db.markAsSent(p.getNumber());
            published++;
            log.info("Лот {} опубликован и помечен как отправленный", p.getNumber());
        }
        return published;
    }

    private void handleParseCommand(long chatId, int messageId, String userIdStr, List<String> adminIds, int maxCount) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            sendMessageWithRetry(chatId, "🚀 Запускаю парсинг...");
            int count = parseAndPublishLots(maxCount, chatId, true);
            sendMessageWithRetry(chatId, "✅ Парсинг завершён, обработано " + count + " лотов");
            log.info("Manual parse completed, {} procurements processed", count);
            try {
                deleteMessage(chatId, messageId);
                log.info("Deleted /parse message {} in chat {}", messageId, chatId);
            } catch (Exception e) {
                log.warn("Failed to delete /parse message {} in chat {}: {}", messageId, chatId, e.getMessage());
            }
        } else {
            try {
                deleteMessage(chatId, messageId);
                log.info("Deleted /parse message {} in chat {} (not admin)", messageId, chatId);
            } catch (Exception e) {
                log.warn("Failed to delete /parse message {} in chat {}: {}", messageId, chatId, e.getMessage());
            }
        }
    }

    private void handleFullParseCommand(long chatId, int messageId, String userIdStr, List<String> adminIds) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            sendMessageWithRetry(chatId, "🚀 Запускаю полный парсинг в чат парсинга...");
            int count = parseAndPublishLots(Integer.MAX_VALUE, Config.getParseGroupId(), true);
            sendMessageWithRetry(chatId, "✅ Полный парсинг завершён, обработано " + count + " лотов");
            log.info("Full parse completed, {} procurements processed", count);
            try {
                deleteMessage(chatId, messageId);
                log.info("Deleted /fullparse message {} in chat {}", messageId, chatId);
            } catch (Exception e) {
                log.warn("Failed to delete /fullparse message {} in chat {}: {}", messageId, chatId, e.getMessage());
            }
        } else {
            try {
                deleteMessage(chatId, messageId);
                log.info("Deleted /fullparse message {} in chat {} (not admin)", messageId, chatId);
            } catch (Exception e) {
                log.warn("Failed to delete /fullparse message {} in chat {}: {}", messageId, chatId, e.getMessage());
            }
        }
    }

    private void handleAddAdminCommand(long chatId, String messageText, String userIdStr, List<String> adminIds) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            String[] parts = messageText.split(" ");
            if (parts.length != 2) {
                sendMessageWithRetry(chatId, "⛔ Формат: /addadmin <chatId>");
                return;
            }
            String newAdminId = parts[1].replace("@SevNTO_bot", "");
            Config.addAdminId(newAdminId);
            sendMessageWithRetry(chatId, "✅ Пользователь " + newAdminId + " добавлен в админы");
            log.info("Added new admin: {}", newAdminId);
        } else {
            sendMessageWithRetry(chatId, "⛔ У вас нет доступа");
            log.info("Access denied for /addadmin command from userId: {}", userIdStr);
        }
    }

    private void handleRemoveAdminCommand(long chatId, String messageText, String userIdStr, List<String> adminIds) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            String[] parts = messageText.split(" ");
            if (parts.length != 2) {
                sendMessageWithRetry(chatId, "⛔ Формат: /removeadmin <chatId>");
                return;
            }
            String removeAdminId = parts[1].replace("@SevNTO_bot", "");
            if (!adminIds.contains(removeAdminId)) {
                sendMessageWithRetry(chatId, "⛔ Пользователь " + removeAdminId + " не найден среди админов");
                return;
            }
            Config.removeAdminId(removeAdminId);
            sendMessageWithRetry(chatId, "✅ Пользователь " + removeAdminId + " удалён из админов");
            log.info("Removed admin: {}", removeAdminId);
        } else {
            sendMessageWithRetry(chatId, "⛔ У вас нет доступа");
            log.info("Access denied for /removeadmin command from userId: {}", userIdStr);
        }
    }
}

package com.example.procurement;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.commands.DeleteMyCommands;
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
import java.net.HttpURLConnection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

import lombok.AllArgsConstructor;

@Slf4j
public class TelegramBot extends TelegramLongPollingBot {
    // Формат цены с пробелами как разделителями тысяч (без копеек для целых)
    private static final java.text.DecimalFormatSymbols DECIMAL_SYMBOLS = new java.text.DecimalFormatSymbols(java.util.Locale.forLanguageTag("ru-RU"));
    static {
        DECIMAL_SYMBOLS.setGroupingSeparator(' ');
        DECIMAL_SYMBOLS.setDecimalSeparator(',');
    }
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,##0.##", DECIMAL_SYMBOLS);
    private static final int TELEGRAM_TEXT_MAX = 4096;
    private static final int TELEGRAM_CAPTION_MAX = 1024;
    private static final ConcurrentHashMap<Long, String> userLotMap = new ConcurrentHashMap<>();
    // adminId -> ReplyContext (userId, userChatId, lotId, вопрос)
    private static final Map<Long, ReplyContext> adminReplyMap = new ConcurrentHashMap<>();
    // adminId -> true если ожидается пересылка лота для удаления
    private static final Map<Long, Boolean> adminDeleteLotMap = new ConcurrentHashMap<>();
    // questionId -> QuestionContext (userId, userChatId, lotId, вопрос)
    private static final Map<String, QuestionContext> questionMap = new ConcurrentHashMap<>();
    
    // Уникальный идентификатор для этого экземпляра бота
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    @AllArgsConstructor
    private static class ReplyContext {
        public final Long userId;
        public final Long userChatId;
        public final String lotId;
        public final String questionText;
    }

    @AllArgsConstructor
    private static class QuestionContext {
        public final Long userId;
        public final Long userChatId;
        public final String lotId;
        public final String questionText;
    }

    public TelegramBot(DefaultBotOptions options) {
        super(options);
        log.info("Initializing TelegramBot instance with ID: {}", instanceId);
        log.info("Bot username: {}, Token length: {}", getBotUsername(),
                getBotToken() != null ? getBotToken().length() : 0);
        initializeCommands();
    }

    public TelegramBot() {
        this(new DefaultBotOptions());
    }

    private void initializeCommands() {
        if (getBotToken() == null || getBotToken().isEmpty()) {
            log.error("Bot token is empty, skipping command initialization");
            return;
        }
        List<BotCommand> adminCommands = new ArrayList<>();
        adminCommands.add(new BotCommand("/parse", "Запустить парсинг лотов"));
        adminCommands.add(new BotCommand("/fullparse", "Принудительный полный парсинг и публикация в чат парсинга"));
        adminCommands.add(new BotCommand("/teststatus", "Тест обновления статуса лота"));
        adminCommands.add(new BotCommand("/testdeadline", "Тест обновления deadline (автоматически берет первый активный лот)"));
        adminCommands.add(new BotCommand("/parsebankrot", "Тест ЦДТРФ: 2 лота в этот чат, без пометки sent"));
        adminCommands.add(new BotCommand("/parsesber", "Тест Сбербанк-АСТ: 2 лота в этот чат, без пометки sent"));
        adminCommands.add(new BotCommand("/stop", "Остановить парсинг (пауза, переживает перезапуск)"));
        adminCommands.add(new BotCommand("/resume", "Возобновить парсинг"));
        adminCommands.add(new BotCommand("/status", "Состояние бота: пауза, диск, база"));
        adminCommands.add(new BotCommand("/addadmin", "Добавить админа (формат: /addadmin <chatId>)"));
        adminCommands.add(new BotCommand("/removeadmin", "Удалить админа (формат: /removeadmin <chatId>)"));
        try {
            SetMyCommands setAdminCommands = new SetMyCommands();
            setAdminCommands.setCommands(adminCommands);
            setAdminCommands.setScope(new BotCommandScopeChat(String.valueOf(Config.getAdminGroupId())));
            execute(setAdminCommands);
            log.info("Admin commands set for admin group {}", Config.getAdminGroupId());

            // Удаляем команды в default-сфере
            DeleteMyCommands deleteDefault = new DeleteMyCommands();
            deleteDefault.setScope(new BotCommandScopeDefault());
            execute(deleteDefault);

            log.info("Cleared commands for default scope");
            // Удаляем команды в чате парсинга (может быть каналом — ошибка некритична)
            try {
                DeleteMyCommands deleteParseGroup = new DeleteMyCommands();
                deleteParseGroup.setScope(new BotCommandScopeChat(String.valueOf(Config.getParseGroupId())));
                execute(deleteParseGroup);
                log.info("Cleared commands for parse group scope");
            } catch (TelegramApiException e) {
                log.warn("Could not clear commands for parse group (channel scope not supported): {}", e.getMessage());
            }
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
                        try {
                            executeWithRetry(msg);
                        } catch (TelegramApiException e) {
                            log.error("Failed to send reply prompt to admin {}: {}", userId, e.getMessage());
                        }
                    }
                }
                return;
            }
            if (callbackData.startsWith("nm_ok|") || callbackData.startsWith("nm_no|")) {
                int cbMessageId = update.getCallbackQuery().getMessage().getMessageId();
                handleNoMatchDecision(callbackData, chatId, cbMessageId, userId);
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
                // Определяем, переслано ли сообщение от бота или из чата парсинга
                boolean isForwardedFromBot = update.getMessage().getForwardFrom() != null
                    && update.getMessage().getForwardFrom().getUserName() != null
                    && update.getMessage().getForwardFrom().getUserName().equals(getBotUsername());
                boolean isForwardedFromParseGroup = update.getMessage().getForwardFromChat() != null
                    && update.getMessage().getForwardFromChat().getId() == Config.getParseGroupId();
                if ((isForwardedFromBot || isForwardedFromParseGroup)
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
                
                // Получаем описание лота вместо ID
                DatabaseManager db = AppContext.getDatabaseManager();
                Procurement procurement = db.getProcurementByNumber(ctx.lotId);
                String lotDescription = procurement != null ? procurement.getTitle() : "лоту";
                
                StringBuilder msgBuilder = new StringBuilder();
                msgBuilder.append("Ответ на ваш вопрос по лоту:\n").append(lotDescription).append("\n\n");
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
                try {
                    executeWithRetry(msg);
                } catch (TelegramApiException e) {
                    log.error("Failed to send delete prompt to admin {}: {}", chatId, e.getMessage());
                }
                return;
            }

            String msgTextLower = mainText.toLowerCase();
            boolean isParseCmd = msgTextLower.equals("/parse") || msgTextLower.equals("/parse@sevnto_bot");
            boolean isFullParseCmd = msgTextLower.equals("/fullparse") || msgTextLower.equals("/fullparse@sevnto_bot");
            boolean isTestStatusCmd = msgTextLower.equals("/teststatus") || msgTextLower.equals("/teststatus@sevnto_bot");
            boolean isTestDeadlineCmd = msgTextLower.startsWith("/testdeadline") || msgTextLower.startsWith("/testdeadline@sevnto_bot");
            boolean isParseBankrotCmd = msgTextLower.equals("/parsebankrot") || msgTextLower.equals("/parsebankrot@sevnto_bot");
            boolean isParseSberCmd = msgTextLower.equals("/parsesber") || msgTextLower.equals("/parsesber@sevnto_bot");
            boolean isStopCmd = msgTextLower.equals("/stop") || msgTextLower.equals("/stop@sevnto_bot");
            boolean isResumeCmd = msgTextLower.equals("/resume") || msgTextLower.equals("/resume@sevnto_bot");
            boolean isStatusCmd = msgTextLower.equals("/status") || msgTextLower.equals("/status@sevnto_bot");
            boolean isRecheckStatusCmd = msgTextLower.equals("/recheckstatus") || msgTextLower.equals("/recheckstatus@sevnto_bot");
            boolean isGeocodeCmd = msgTextLower.startsWith("/geocode");
            boolean isAddAdminCmd = msgTextLower.startsWith("/addadmin") || msgTextLower.startsWith("/addadmin@sevnto_bot");
            boolean isRemoveAdminCmd = msgTextLower.startsWith("/removeadmin") || msgTextLower.startsWith("/removeadmin@sevnto_bot");
            boolean isCommand = isParseCmd || isFullParseCmd || isTestStatusCmd || isTestDeadlineCmd || isParseBankrotCmd || isParseSberCmd || isAddAdminCmd || isRemoveAdminCmd;

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
                try {
                    executeWithRetry(sendMessage);
                } catch (TelegramApiException e) {
                    log.error("Failed to send lot start message to user {}: {}", userId, e.getMessage());
                }
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
                // Ответ на запрос точки: админ присылает ссылку на Яндекс.Карты reply-сообщением
                if (handlePointReply(chatId, update.getMessage(), mainText, userIdStr, adminIds)) {
                    return;
                }
                if (isParseCmd) {
                    handleParseCommand(chatId, messageId, userIdStr, adminIds, 2);
                    return;
                } else if (isFullParseCmd) {
                    handleFullParseCommand(chatId, messageId, userIdStr, adminIds);
                    return;
                } else if (isTestStatusCmd) {
                    handleTestStatusCommand(chatId, messageId, userIdStr, adminIds);
                    return;
                } else if (isTestDeadlineCmd) {
                    handleTestDeadlineCommand(chatId, messageId, mainText, userIdStr, adminIds);
                    return;
                } else if (isParseBankrotCmd) {
                    handleParseBankrotCommand(chatId, messageId, userIdStr, adminIds, 2);
                    return;
                } else if (isParseSberCmd) {
                    handleParseSberCommand(chatId, messageId, userIdStr, adminIds, 2);
                    return;
                } else if (isStopCmd) {
                    handleStopCommand(chatId, userIdStr, adminIds);
                    return;
                } else if (isResumeCmd) {
                    handleResumeCommand(chatId, userIdStr, adminIds);
                    return;
                } else if (isStatusCmd) {
                    handleStatusCommand(chatId, userIdStr, adminIds);
                    return;
                } else if (isRecheckStatusCmd) {
                    handleRecheckStatusCommand(chatId, userIdStr, adminIds);
                    return;
                } else if (isGeocodeCmd) {
                    handleGeocodeCommand(chatId, mainText, userIdStr, adminIds);
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

    public boolean sendProcurementMessage(long chatId, Procurement procurement) {
        resolveCadastralPoint(procurement);
        String lotType = "";
        String priceLabel = "";
        boolean isCdtrf = procurement.getSource() != null && procurement.getSource().contains("ЦДТРФ");

        // Для ЦДТРФ используем lotType из парсера
        if (isCdtrf) {
            lotType = procurement.getLotType() != null ? procurement.getLotType() : "Реализация имущества должников";
            priceLabel = "Цена за договор";
        } else if (procurement.getBiddTypeName() != null && procurement.getBiddTypeName().toLowerCase().contains("реализация имущества должников")) {
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
        // Собираем части сообщения: заголовок, описание (title) и детали
        StringBuilder header = new StringBuilder();
        header.append("<b>").append(lotType).append("</b>\n\n");
        String originalTitle = procurement.getTitle() != null ? procurement.getTitle() : "";
        StringBuilder details = new StringBuilder();

        // Определяем тип договора для правильного отображения цен
        boolean isRentalContract = procurement.getContractTypeName() != null &&
            procurement.getContractTypeName().toLowerCase().contains("аренды");

        if (isRentalContract && procurement.getMonthlyPrice() != null && procurement.getPrice() != null) {
            // Для договоров аренды показываем и месячную, и годовую цену
            String formattedMonthlyPrice = DECIMAL_FORMAT.format(procurement.getMonthlyPrice());
            details.append("💰Аренда в месяц: ").append(formattedMonthlyPrice).append(" ₽\n");

            String formattedYearlyPrice = DECIMAL_FORMAT.format(procurement.getPrice());
            details.append("💰Аренда в год: ").append(formattedYearlyPrice).append(" ₽\n");
        } else if (isRentalContract && procurement.getMonthlyPrice() != null) {
            // Только месячная цена известна
            String formattedMonthlyPrice = DECIMAL_FORMAT.format(procurement.getMonthlyPrice());
            details.append("💰Аренда в месяц: ").append(formattedMonthlyPrice).append(" ₽\n");
        } else if (isRentalContract && procurement.getPrice() != null) {
            // Только годовая цена известна
            String formattedPrice = DECIMAL_FORMAT.format(procurement.getPrice());
            details.append("💰Аренда в год: ").append(formattedPrice).append(" ₽\n");
            // Рассчитаем месячную
            String formattedMonthlyPrice = DECIMAL_FORMAT.format(procurement.getPrice() / 12.0);
            details.append("💰Аренда в месяц: ").append(formattedMonthlyPrice).append(" ₽\n");
        } else if (procurement.getPrice() != null) {
            // Для купли-продажи или неизвестного типа
            String formattedPrice = DECIMAL_FORMAT.format(procurement.getPrice());
            details.append("💰").append(priceLabel).append(": ").append(formattedPrice).append(" ₽\n");
        }

        if (procurement.getDeposit() != null) {
            String formattedDeposit = DECIMAL_FORMAT.format(procurement.getDeposit());
            details.append("💰Задаток: ").append(formattedDeposit).append(" ₽\n");
        }
        boolean isDebtor = procurement.getBiddTypeName() != null && procurement.getBiddTypeName().toLowerCase().contains("реализация имущества должников");
        if (!isDebtor && procurement.getDepositRecipientName() != null && !procurement.getDepositRecipientName().isEmpty()) {
            String shortOrg = getShortOrgNameFull(procurement.getDepositRecipientName());
            details.append("🏛Организатор торгов: ").append(shortOrg).append("\n");
        }
        details.append("🧭г Севастополь\n");
        if (procurement.getContractTerm() != null) {
            // Для ЦДТРФ в contractTerm храним задаток
            if (isCdtrf) {
                details.append(procurement.getContractTerm()).append("\n");
            } else if (isRentalContract && isMeaningfulTerm(procurement.getContractTerm())) {
                // Срок договора — только для лотов аренды и только если он осмысленный
                details.append("📅Срок договора: ").append(procurement.getContractTerm()).append("\n");
            }
        }
        if (procurement.getDeadline() != null) {
            String formattedDeadline = procurement.getDeadline();
            try {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(procurement.getDeadline());
                formattedDeadline = odt.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            } catch (Exception ignore) {}
            details.append("⏰Подача до: <u>").append(formattedDeadline).append("</u>\n\n");
        }
        details.append("Заинтересовал лот? <a href=\"https://t.me/").append(getBotUsername()).append("?start=lot_").append(procurement.getNumber()).append("\">Пиши</a> или звони 88007078692");

        // Функция сборки с ограничением длины, сначала урезает описание (title)
        String assembleWithLimitForCaption = assembleWithLimit(header.toString(), originalTitle, details.toString(), TELEGRAM_CAPTION_MAX);
        String assembleWithLimitForText = assembleWithLimit(header.toString(), originalTitle, details.toString(), TELEGRAM_TEXT_MAX);
        
        Integer sentMessageId = null;
        boolean lotPublished = false;
        // Картинки публикуем для всех источников. Раньше здесь стояло исключение для
        // Сбербанк-АСТ (у него нет фото), но оно было мёртвым: сравнивалось с "Сбербанк-АСТ",
        // а парсер пишет source="sberbank-ast.ru". Теперь SberAst отдаёт картинку-заглушку,
        // как ЦДТРФ, и отдельное условие не нужно.
        if (procurement.getImageUrls() != null && !procurement.getImageUrls().isEmpty()) {
            int maxImages = Math.min(4, procurement.getImageUrls().size());
            List<String> urls = procurement.getImageUrls().subList(0, maxImages);
            try {
                if (urls.size() == 1) {
                    String url = urls.get(0);
                    log.info("IMAGE_URL for procurement {}: {}", procurement.getNumber(), url);
                    InputStream in = null;
                    
                    // Проверяем, это дефолтная картинка из ресурсов или URL
                    if (url.startsWith("default_") || !url.startsWith("http")) {
                        // Загружаем из ресурсов
                        in = getClass().getClassLoader().getResourceAsStream("images/" + url);
                        log.info("Loading default image from resources: images/{}", url);
                    } else {
                        // Загружаем по URL
                        in = downloadImage(url);
                    }
                    
                    if (in != null) {
                        InputFile inputFile = new InputFile(in, "image.jpg");
                        SendPhoto photo = new SendPhoto();
                        photo.setChatId(chatId);
                        photo.setPhoto(inputFile);
                        photo.setCaption(assembleWithLimitForCaption);
                        photo.setParseMode("HTML");
                        photo.setDisableNotification(true);
                        sentMessageId = executeWithRetry(photo);
                        in.close();
                        log.info("Sent 1 image for procurement: {}", procurement.getNumber());
                    } else {
                        log.warn("Image unavailable for procurement {}, skipping publish", procurement.getNumber());
                        // Не публикуем без картинки — лот будет повторно обработан на следующем запуске
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
                                photo.setCaption(assembleWithLimitForCaption);
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
                        mediaGroup.setDisableNotification(true);
                        sentMessageId = executeWithRetry(mediaGroup); // выбросит исключение → поймает catch ниже
                        lotPublished = true;
                        log.info("Sent {} images for procurement: {} (downloaded)", media.size(), procurement.getNumber());
                    } else {
                        log.warn("No images could be downloaded for procurement {}, skipping publish", procurement.getNumber());
                        // Не публикуем без картинок — лот будет повторно обработан на следующем запуске
                    }
                    for (InputStream s : streams) try { s.close(); } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                log.error("Failed to download/send images for procurement {}, skipping publish: {}", procurement.getNumber(), e.getMessage());
                // Не публикуем без картинок — лот будет повторно обработан на следующем запуске
            }
        } else {
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(chatId);
            sendMessage.setText(assembleWithLimitForText);
            sendMessage.setParseMode("HTML");
            sendMessage.setDisableNotification(true);
            try {
                sentMessageId = executeWithRetry(sendMessage);
            } catch (TelegramApiException e) {
                log.error("Failed to send message for procurement {}: {}", procurement.getNumber(), e.getMessage());
            }
        }
        // Сохраняем связь messageId <-> номер лота
        if (sentMessageId != null) {
            lotPublished = true;
            DatabaseManager db = AppContext.getDatabaseManager();
            db.saveMessageId(procurement.getNumber(), sentMessageId, chatId);
        }

        // Ссылку на Яндекс.Карты отправляем только если лот был успешно опубликован
        if (lotPublished) {
            String finalUrl = null;
            boolean hasPoint = procurement.getLat() != null && procurement.getLon() != null;
            // Координатам верим, если они из ЕГРН (по кадастровому номеру) либо если точка из torgi
            // не продублирована у лота с другим адресом. Общая точка = адрес организатора,
            // проставленный один на всё многолотовое извещение, — метка уехала бы в центр города.
            boolean pointTrusted = hasPoint
                    && (Procurement.POINT_SOURCE_CADASTRAL.equals(procurement.getPointSource())
                        || Procurement.POINT_SOURCE_MANUAL.equals(procurement.getPointSource())
                        || !AppContext.getDatabaseManager().isSharedPoint(
                                procurement.getLat(), procurement.getLon(), procurement.getAddress()));

            if (pointTrusted) {
                finalUrl = createYandexMapsPointLink(procurement.getLat(), procurement.getLon());
            } else if (procurement.getAddress() != null && !procurement.getAddress().isEmpty()) {
                // Fallback: текстовый геокодер по оптимизированному адресу
                String addressForMap = createOptimizedAddress(procurement.getAddress(), procurement.getCadastralNumber());
                boolean informative = addressForMap != null && !addressForMap.isEmpty()
                        && !addressForMap.equalsIgnoreCase("г Севастополь")
                        && !addressForMap.equalsIgnoreCase("г. Севастополь");
                if (informative) {
                    finalUrl = createYandexMapsShortLink(addressForMap);
                } else if (hasPoint) {
                    // Адрес без улицы и дома геокодер не найдёт — сомнительная точка всё же лучше, чем ничего
                    finalUrl = createYandexMapsPointLink(procurement.getLat(), procurement.getLon());
                }
            } else if (hasPoint) {
                finalUrl = createYandexMapsPointLink(procurement.getLat(), procurement.getLon());
            }
            if (finalUrl != null) {
                try {
                    org.telegram.telegrambots.meta.api.methods.send.SendMessage linkMsg = new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
                    linkMsg.setChatId(chatId);
                    linkMsg.setText(finalUrl); // Без Markdown-экранирования
                    linkMsg.setDisableWebPagePreview(false); // Разрешаем превью
                    linkMsg.setDisableNotification(true);
                    executeWithRetry(linkMsg);
                } catch (Exception e) {
                    log.warn("Не удалось отправить ссылку на Яндекс карты для лота {}: {}", procurement.getNumber(), e.getMessage());
                }
            }
        }
        return lotPublished;
    }

    private void sendUserQuestionToAdmins(Long userId, String username, String lotId, String lotTitle, String userMessage) {
        String userLink = username != null ? "<a href=\"tg://user?id=" + userId + "\">@" + username + "</a>" : "tg://user?id=" + userId;

        // Получаем ссылку на лот
        DatabaseManager db = AppContext.getDatabaseManager();
        Procurement procurement = db.getProcurementByNumber(lotId);
        String lotLink = procurement != null && procurement.getLink() != null ? procurement.getLink() : "";

        StringBuilder msgBuilder = new StringBuilder();
        msgBuilder.append("Вопрос от пользователя ").append(userLink).append("\n\n");

        // Добавляем ссылку на лот, если она есть
        if (!lotLink.isEmpty()) {
            msgBuilder.append(lotLink).append("\n\n");
        }

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
        try {
            executeWithRetry(msg);
        } catch (TelegramApiException e) {
            log.error("Failed to send admin notification: {}", e.getMessage());
        }
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

        // Сначала пробуем извлечь номер лота из entities (text_link с ?start=lot_XXX)
        // Это надёжнее, чем поиск по заголовку
        java.util.List<org.telegram.telegrambots.meta.api.objects.MessageEntity> entities =
            update.getMessage().getEntities();
        if (entities == null) {
            entities = update.getMessage().getCaptionEntities();
        }
        if (entities != null) {
            java.util.regex.Pattern lotPattern = java.util.regex.Pattern.compile("[?&]start=lot_([^&\\s]+)");
            for (org.telegram.telegrambots.meta.api.objects.MessageEntity entity : entities) {
                if ("text_link".equals(entity.getType()) && entity.getUrl() != null) {
                    java.util.regex.Matcher m = lotPattern.matcher(entity.getUrl());
                    if (m.find()) {
                        procurementNumber = m.group(1);
                        log.info("[BOT] Номер лота извлечён из entity URL: {}", procurementNumber);
                        break;
                    }
                }
            }
        }

        // Fallback: поиск по заголовку (берём самый новый совпадающий лот)
        if (procurementNumber == null && mainText != null && !mainText.isEmpty()) {
            String mainTextNorm = mainText.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").toLowerCase().trim();
            try (java.sql.Connection conn = java.sql.DriverManager.getConnection(Config.getDbUrl().startsWith("jdbc:") ? Config.getDbUrl() : "jdbc:sqlite:" + Config.getDbUrl())) {
                java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT number, title FROM procurements ORDER BY rowid DESC");
                java.sql.ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String dbTitle = rs.getString("title");
                    String dbTitleNorm = dbTitle != null ? dbTitle.replaceAll("\\s+", " ").toLowerCase() : "";
                    if (dbTitle != null && mainTextNorm.contains(dbTitleNorm)) {
                        procurementNumber = rs.getString("number");
                        log.info("[BOT] Номер лота найден по заголовку (fallback): {}", procurementNumber);
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
            // Источник-агностично: берём реальную ссылку лота из БД (torgi/ЦДТРФ/SberAst),
            // а не собираем torgi-шаблон — иначе для новых источников ссылка будет неверной.
            String lotUrl = null;
            Procurement forwarded = AppContext.getDatabaseManager().getProcurementByNumber(procurementNumber);
            if (forwarded != null && forwarded.getLink() != null && !forwarded.getLink().isEmpty()) {
                lotUrl = forwarded.getLink();
            } else {
                lotUrl = "https://torgi.gov.ru/new/public/lots/lot/" + procurementNumber + "/(lotInfo:info)?fromRec=false";
            }
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
        message.setText(text);
        // Не используем ParseMode для простоты и надежности
        try {
            executeWithRetry(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to {}: {}", chatId, e.getMessage());
        }
    }

    public void sendMessageWithMarkdown(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        String escapedText = escapeMarkdownV2(text);
        message.setText(escapedText);
        message.setParseMode("MarkdownV2");
        try {
            executeWithRetry(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send markdown message to {}: {}", chatId, e.getMessage());
        }
    }

    public void sendMessageWithHTML(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setParseMode("HTML");
        try {
            executeWithRetry(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send HTML message to {}: {}", chatId, e.getMessage());
        }
    }

    private SendMessage createHTMLMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId);
        message.setText(text);
        message.setParseMode("HTML");
        message.setDisableNotification(true);
        return message;
    }

    /**
     * Просит админов проставить точку вручную: бот не смог получить координаты из ЕГРН.
     * Ответ ожидается reply на это сообщение — ссылкой на Яндекс.Карты.
     */
    public void requestPointFromAdmins(Procurement p) {
        DatabaseManager db = AppContext.getDatabaseManager();
        if (db == null || db.hasPendingPointRequest(p.getNumber())) {
            return; // по этому лоту уже спрашивали, второй раз не беспокоим
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📍 Нужна точка на карте\n\n")
          .append("Лот: ").append(p.getNumber()).append("\n");
        if (p.getTitle() != null) {
            sb.append(p.getTitle().substring(0, Math.min(200, p.getTitle().length()))).append("\n");
        }
        if (p.getAddress() != null && !p.getAddress().isEmpty()) {
            sb.append("Адрес: ").append(p.getAddress()).append("\n");
        }
        if (p.getCadastralNumber() != null && !p.getCadastralNumber().isEmpty()) {
            sb.append("Кадастровый номер: ").append(p.getCadastralNumber()).append("\n");
        }
        if (p.getLink() != null) {
            sb.append(p.getLink()).append("\n");
        }
        sb.append("\nКоординаты из ЕГРН получить не удалось, а точка от организатора недостоверна — ")
          .append("лот не публикуется.\n")
          .append("Ответьте на это сообщение ссылкой на Яндекс.Карты с нужной точкой — ")
          .append("бот сразу опубликует лот с ней.");
        try {
            org.telegram.telegrambots.meta.api.methods.send.SendMessage msg =
                    new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
            msg.setChatId(Config.getAdminGroupId());
            msg.setText(sb.toString());
            msg.setDisableWebPagePreview(true);
            Integer messageId = executeWithRetry(msg);
            if (messageId != null) {
                db.savePendingPointRequest(p.getNumber(), messageId, Config.getAdminGroupId());
                log.info("Запрошена точка у админов по лоту {} (сообщение {})", p.getNumber(), messageId);
            }
        } catch (Exception e) {
            log.warn("Не удалось запросить точку у админов по лоту {}: {}", p.getNumber(), e.getMessage());
        }
    }

    /**
     * Обрабатывает ответ админа со ссылкой на карту: ставит точку и публикует лот.
     * @return true, если сообщение было ответом на запрос точки (дальше его обрабатывать не нужно)
     */
    private boolean handlePointReply(long chatId, org.telegram.telegrambots.meta.api.objects.Message message,
                                     String text, String userIdStr, List<String> adminIds) {
        if (message == null || userIdStr == null || !adminIds.contains(userIdStr)) {
            return false;
        }
        org.telegram.telegrambots.meta.api.objects.Message replyTo = message.getReplyToMessage();
        if (replyTo == null) {
            return false;
        }
        DatabaseManager db = AppContext.getDatabaseManager();
        String lotNumber = db.getPendingLotByMessage(chatId, replyTo.getMessageId());
        if (lotNumber == null) {
            return false;
        }
        // Геопозиция Telegram — самый простой для админа способ: «Прикрепить → Геопозиция»
        double[] point = null;
        if (message.getLocation() != null) {
            point = new double[]{message.getLocation().getLatitude(), message.getLocation().getLongitude()};
            log.info("Точка получена геопозицией Telegram: {},{}", point[0], point[1]);
        }
        if (point == null) {
            point = parseMapPoint(text);
        }
        if (point == null) {
            log.warn("Не удалось разобрать точку из ответа админа по лоту {}: {}", lotNumber,
                    text == null ? "(сообщение без текста)" : text);
            sendMessageWithRetry(chatId, "Не разобрал координаты.\n\n"
                    + "Если это ссылка «Поделиться» на найденный объект, координат в ней нет: "
                    + "она ведёт на карточку объекта. Нужна ссылка именно на место:\n"
                    + "• Яндекс.Карты: правой кнопкой по нужной точке → «Что здесь?», затем скопировать "
                    + "ссылку из адресной строки браузера;\n"
                    + "• геопозиция: «Прикрепить → Геопозиция» ответом на это сообщение;\n"
                    + "• просто два числа: 44.5801, 33.4980.");
            return true;
        }
        Procurement p = db.getProcurementByNumber(lotNumber);
        if (p == null) {
            sendMessageWithRetry(chatId, "Лот " + lotNumber + " больше не найден в базе.");
            db.deletePendingPointRequest(lotNumber);
            return true;
        }
        // Дообогащаем лот перед публикацией: ссылки на фотографии в БД не хранятся,
        // а сюда лот приходит именно из БД — без этого карточка уходит без фото.
        // Точку админа накладываем ПОСЛЕ обогащения, иначе enrich вернёт координаты
        // организатора и затрёт её.
        if (p.getSource() != null && p.getSource().contains("Torgi")) {
            ParserService parser = AppContext.getParserService();
            if (parser != null) {
                p.setImageUrls(new java.util.ArrayList<>());
                parser.enrichOne(p);
            }
        }
        p.setLat(point[0]);
        p.setLon(point[1]);
        p.setPointSource(Procurement.POINT_SOURCE_MANUAL);
        db.updateCoordinates(lotNumber, point[0], point[1], Procurement.POINT_SOURCE_MANUAL);
        log.info("Админ задал точку для лота {}: {},{}", lotNumber, point[0], point[1]);

        boolean sent = sendProcurementMessage(Config.getParseGroupId(), p);
        if (sent && db.markAsSent(lotNumber)) {
            db.clearFiltered(lotNumber);
            db.deletePendingPointRequest(lotNumber);
            sendMessageWithRetry(chatId, "✅ Лот " + lotNumber + " опубликован с указанной точкой.");
        } else {
            sendMessageWithRetry(chatId, "⚠️ Точку сохранил, но опубликовать лот " + lotNumber
                    + " не удалось. Попробует следующий прогон.");
        }
        return true;
    }

    /** Широта в пределах России, для распознавания порядка чисел. */
    private static boolean looksLikeLat(double v) { return v >= 41 && v <= 82; }
    /** Долгота в пределах России. */
    private static boolean looksLikeLon(double v) { return v >= 19 && v <= 180; }

    /**
     * Достаёт координаты из ответа админа: ссылка на карту или пара чисел.
     *
     * Поддерживаются форматы, которые реально дают кнопки «Поделиться» и «Что здесь?»:
     * Яндекс.Карты (ll, pt, whatshere[point] — у них порядок долгота,широта), Google Maps
     * (@шир,долг и q=шир,долг), 2ГИС (m=долг,шир), а также просто пара чисел, скопированная
     * из карточки объекта. Короткие ссылки (yandex.ru/maps/-/…, maps.app.goo.gl) разворачиваются
     * по редиректу.
     *
     * @return массив {широта, долгота} либо null
     */
    static double[] parseMapPoint(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // Явные параметры карт, где порядок долгота,широта
        double[] p = matchPair(text, "(?:pt|ll|sll|m|whatshere(?:\\[|%5B)point(?:\\]|%5D))=(-?\\d+\\.\\d+)(?:,|%2C)(-?\\d+\\.\\d+)", false);
        if (p != null) return p;
        // Google Maps: @широта,долгота и q=широта,долгота
        p = matchPair(text, "[@?&](?:q=|ll=)?(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)", true);
        if (p != null) return p;

        String expanded = expandShortLink(text);
        if (expanded != null) {
            double[] fromLink = parseMapPoint(expanded);
            if (fromLink != null) return fromLink;
        }
        // Просто два числа в сообщении: «44.5801, 33.4980»
        return matchPair(text, "(-?\\d{1,3}\\.\\d{3,}+)[,;\\s]+(-?\\d{1,3}\\.\\d{3,}+)", true);
    }

    /**
     * @param latFirst ожидается ли широта первой; порядок всё равно перепроверяется по диапазонам —
     *                 люди присылают числа в обоих вариантах, а перепутанные координаты уводят
     *                 метку в другую страну
     */
    private static double[] matchPair(String text, String regex, boolean latFirst) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(text);
        if (!m.find()) {
            return null;
        }
        try {
            double a = Double.parseDouble(m.group(1));
            double b = Double.parseDouble(m.group(2));
            double lat = latFirst ? a : b;
            double lon = latFirst ? b : a;
            if (!looksLikeLat(lat) && looksLikeLat(lon) && looksLikeLon(lat)) {
                double t = lat; lat = lon; lon = t;
            }
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) {
                return null;
            }
            return new double[]{lat, lon};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Разворачивает короткую ссылку карт: координаты прячутся за редиректом. */
    private static String expandShortLink(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[\\w.-]*(?:yandex\\.[a-z]+/maps/-/|maps\\.app\\.goo\\.gl/|goo\\.gl/maps/|go\\.2gis\\.com/)\\S+")
                .matcher(text);
        if (!m.find()) {
            return null;
        }
        String url = m.group();
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location != null) {
                log.info("Короткая ссылка развёрнута: {}", location.length() > 160 ? location.substring(0, 160) : location);
            }
            return location;
        } catch (Exception e) {
            log.warn("Не удалось развернуть короткую ссылку {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Подтягивает координаты объекта из ЕГРН по кадастровому номеру — непосредственно перед
     * публикацией. Раньше это делалось при обогащении каждого лота, но туда попадают и уже
     * опубликованные, и будущие отсевы фильтров: лишние обращения к НСПД быстро упирались
     * в ограничение по частоте. Повторно не запрашиваем — у лота уже стоит признак cadastral.
     */
    private void resolveCadastralPoint(Procurement procurement) {
        if (procurement == null
                || Procurement.POINT_SOURCE_CADASTRAL.equals(procurement.getPointSource())
                || Procurement.POINT_SOURCE_MANUAL.equals(procurement.getPointSource())) {
            return;
        }
        CadastralGeocoder.Point egrnPoint = CadastralGeocoder.resolve(procurement.getCadastralNumber());
        if (egrnPoint == null) {
            return;
        }
        procurement.setLat(egrnPoint.getLat());
        procurement.setLon(egrnPoint.getLon());
        procurement.setPointSource(Procurement.POINT_SOURCE_CADASTRAL);
        DatabaseManager db = AppContext.getDatabaseManager();
        if (db != null) {
            // Сохраняем, иначе при повторной публикации или правке карточки координаты
            // пришлось бы запрашивать заново
            db.updateCoordinates(procurement.getNumber(), egrnPoint.getLat(), egrnPoint.getLon(),
                    Procurement.POINT_SOURCE_CADASTRAL);
        }
    }

    /**
     * Срок договора пригоден к показу, если это не пусто и не ноль. Организаторы регулярно
     * заполняют все компоненты срока нулями, а «Срок договора: 0» в карточке только сбивает с толку.
     */
    private static boolean isMeaningfulTerm(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String cleaned = term.trim();
        return !cleaned.equals("0") && !cleaned.matches("0+([.,]0+)?");
    }

    private Integer executeWithRetry(Object method) throws TelegramApiException {
        int maxRetries = 3;
        int delayMs = 3000;
        TelegramApiException lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (method instanceof SendMessage) {
                    return execute((SendMessage) method).getMessageId();
                } else if (method instanceof SendMediaGroup) {
                    // Подпись лота Telegram кладёт в ПЕРВОЕ сообщение альбома — именно его id нужен,
                    // чтобы потом дописать в карточку «Аукцион состоялся / не состоялся».
                    java.util.List<org.telegram.telegrambots.meta.api.objects.Message> sent =
                            execute((SendMediaGroup) method);
                    return (sent != null && !sent.isEmpty()) ? sent.get(0).getMessageId() : null;
                } else if (method instanceof SendPhoto) {
                    return execute((SendPhoto) method).getMessageId();
                }
                return null;
            } catch (TelegramApiException e) {
                lastException = e;
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean is429 = msg.contains("429") || msg.contains("too many requests");
                boolean isNetworkError = msg.contains("timeout") || msg.contains("connect")
                        || msg.contains("failed to respond") || msg.contains("nohttpresponse")
                        || msg.contains("reset") || msg.contains("refused");
                if (is429) {
                    int retryAfter = 30;
                    java.util.regex.Matcher m429 = java.util.regex.Pattern.compile("retry after (\\d+)").matcher(msg);
                    if (m429.find()) retryAfter = Integer.parseInt(m429.group(1));
                    log.warn("Rate limited (429), waiting {}s before retry (attempt {}/{})...", retryAfter, attempt, maxRetries);
                    try { Thread.sleep((retryAfter + 2) * 1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    attempt--; // не считаем 429 как попытку
                } else if (isNetworkError && attempt < maxRetries) {
                    log.warn("Attempt {}/{} failed ({}): {}. Retrying in {}ms...",
                            attempt, maxRetries, method.getClass().getSimpleName(), e.getMessage(), delayMs);
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    delayMs *= 2;
                } else {
                    throw e;
                }
            }
        }
        throw lastException;
    }

    // Собирает итоговый текст из: header + title + details, при необходимости урезая title
    private String assembleWithLimit(String header, String title, String details, int maxLen) {
        if (header == null) header = "";
        if (title == null) title = "";
        if (details == null) details = "";

        String separator = "\n\n";
        String full = header + title + separator + details;
        if (full.length() <= maxLen) {
            return full;
        }

        int reserved = header.length() + separator.length() + details.length();
        int allowedForTitle = Math.max(0, maxLen - reserved);
        if (allowedForTitle == 0) {
            // Ничего не помещается для title — вернём header + details, обрезав details в самом конце при необходимости
            String headAndDetails = header + separator + details;
            if (headAndDetails.length() <= maxLen) return headAndDetails;
            return headAndDetails.substring(0, Math.max(0, maxLen - 1)) + "…";
        }

        String trimmedTitle = title;
        if (title.length() > allowedForTitle) {
            // Стараться резать по границе слова
            int cut = allowedForTitle;
            int lastSpace = title.lastIndexOf(' ', Math.max(0, cut - 1));
            if (lastSpace > 0 && lastSpace >= cut - 40) {
                cut = lastSpace;
            }
            trimmedTitle = title.substring(0, Math.max(0, cut));
            if (!trimmedTitle.endsWith("…")) {
                trimmedTitle = trimmedTitle.replaceAll("[\\s\n]+$", "");
                trimmedTitle = trimmedTitle + "…";
            }
        }

        String result = header + trimmedTitle + separator + details;
        if (result.length() > maxLen) {
            // На всякий случай, если из-за многоточия превысили
            return result.substring(0, Math.max(0, maxLen - 1)) + "…";
        }
        return result;
    }

    private String escapeMarkdownV2(String text) {
        if (text == null) return "";
        return text
                .replace("_", "\\_")
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

    private String getShortOrgNameFull(String fullName) {
        if (fullName == null) return "";
        int quoteStart = fullName.indexOf('"');
        int quoteEnd = fullName.indexOf('"', quoteStart + 1);
        if (quoteStart != -1 && quoteEnd != -1 && quoteEnd > quoteStart) {
            String before = fullName.substring(0, quoteStart).trim();
            String inQuotes = fullName.substring(quoteStart + 1, quoteEnd).trim();
            StringBuilder abbrBefore = new StringBuilder();
            for (String word : before.split("[\\s,]+")) {
                if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
                    abbrBefore.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            StringBuilder abbrQuotes = new StringBuilder();
            for (String word : inQuotes.split("[\\s,]+")) {
                if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
                    abbrQuotes.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            return abbrBefore + " \"" + abbrQuotes + "\"";
        } else {
            StringBuilder abbr = new StringBuilder();
            for (String word : fullName.split("[\\s,]+")) {
                if (!word.isEmpty() && Character.isLetter(word.charAt(0))) {
                    abbr.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            return abbr.toString();
        }
    }

    private InputStream downloadImage(String imageUrl) {
        return downloadImage(imageUrl, 0);
    }

    private InputStream downloadImage(String imageUrl, int redirectCount) {
        if (redirectCount > 5) {
            log.warn("Too many redirects (>5) for image: {}", imageUrl);
            return null;
        }

        try {
            java.net.URL url = java.net.URI.create(imageUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // Полноценные браузерные заголовки для обхода анти-бот защиты
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "identity"); // Не сжимаем, чтобы получить чистый поток
            conn.setRequestProperty("Referer", "https://torgi.gov.ru/");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Sec-Fetch-Dest", "image");
            conn.setRequestProperty("Sec-Fetch-Mode", "no-cors");
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin");

            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false); // Обрабатываем редиректы вручную

            int responseCode = conn.getResponseCode();

            // Обработка редиректов (301, 302, 303, 307, 308)
            if (responseCode >= 300 && responseCode < 400) {
                String redirectUrl = conn.getHeaderField("Location");
                if (redirectUrl != null) {
                    log.info("Image redirect {} -> {}", imageUrl, redirectUrl);
                    // Если относительный URL, делаем абсолютным
                    if (redirectUrl.startsWith("/")) {
                        redirectUrl = url.getProtocol() + "://" + url.getHost() + redirectUrl;
                    }
                    conn.disconnect();
                    return downloadImage(redirectUrl, redirectCount + 1);
                }
            }

            if (responseCode != 200) {
                log.warn("Failed to download image from {}: HTTP {}", imageUrl, responseCode);
                return null;
            }

            // Проверяем Content-Type - должен быть image/*
            String contentType = conn.getContentType();
            if (contentType != null && !contentType.startsWith("image/")) {
                log.warn("Unexpected content type for image {}: {}", imageUrl, contentType);
                // Всё равно пробуем, может это картинка без правильного типа
            }

            return conn.getInputStream();
        } catch (java.net.SocketTimeoutException e) {
            log.warn("Timeout downloading image from {}: {}", imageUrl, e.getMessage());
            return null;
        } catch (java.io.IOException e) {
            log.warn("IO error downloading image from {}: {}", imageUrl, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Failed to download image from {}: {}", imageUrl, e.getMessage());
            return null;
        }
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

    /**
     * Парсит и публикует лоты. Использует общий сервис обработки.
     */
    private int parseAndPublishLots(int maxCount, long chatId, boolean notifyAdminOnNoMatch, boolean markSent) {
        ProcurementProcessingService processingService = AppContext.getProcessingService();
        return processingService.parseAndPublishDefault(maxCount, chatId, notifyAdminOnNoMatch, markSent);
    }

    private void handleParseCommand(long chatId, int messageId, String userIdStr, List<String> adminIds, int maxCount) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            sendMessageWithRetry(chatId, "🚀 Тестовый парсинг torgi (в этот чат, без пометки sent)...");
            // Тест: публикуем в чат запуска, НЕ метим sent → планировщик всё равно выложит лот в канал.
            int count = parseAndPublishLots(maxCount, chatId, true, false);
            sendMessageWithRetry(chatId, "✅ Тестовый парсинг torgi завершён: " + count + " лотов");
            log.info("Manual /parse (torgi test, markSent=false) completed, {} lots", count);
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

    /**
     * Тестовый парсинг ЦДТРФ: публикует лоты в чат запуска БЕЗ пометки sent
     * (не «съедает» лоты — планировщик отработает их независимо).
     */
    private void handleParseBankrotCommand(long chatId, int messageId, String userIdStr, List<String> adminIds, int maxCount) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            sendMessageWithRetry(chatId, "🚀 Тест ЦДТРФ (в этот чат, без пометки sent)...");
            int count = AppContext.getProcessingService().parseAndPublishCdtrf(maxCount, chatId, false);
            sendMessageWithRetry(chatId, "✅ Тест ЦДТРФ завершён: " + count + " лотов");
            log.info("Manual /parsebankrot (ЦДТРФ test, markSent=false) completed, {} lots", count);
        }
        try {
            deleteMessage(chatId, messageId);
        } catch (Exception e) {
            log.warn("Failed to delete /parsebankrot message: {}", e.getMessage());
        }
    }

    /**
     * Тестовый парсинг Сбербанк-АСТ: публикует лоты в чат запуска БЕЗ пометки sent.
     */
    private void handleParseSberCommand(long chatId, int messageId, String userIdStr, List<String> adminIds, int maxCount) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            sendMessageWithRetry(chatId, "🚀 Тест Сбербанк-АСТ (в этот чат, без пометки sent)...");
            int count = AppContext.getProcessingService().parseAndPublishSberAst(maxCount, chatId, false);
            sendMessageWithRetry(chatId, "✅ Тест Сбербанк-АСТ завершён: " + count + " лотов");
            log.info("Manual /parsesber (SberAst test, markSent=false) completed, {} lots", count);
        }
        try {
            deleteMessage(chatId, messageId);
        } catch (Exception e) {
            log.warn("Failed to delete /parsesber message: {}", e.getMessage());
        }
    }

    /**
     * /stop — ставит парсинг на паузу. Нужна для случая «бот публикует не то»:
     * раньше остановить его можно было только через SSH, и до этого момента лоты
     * продолжали уходить в канал каждым прогоном.
     * Флаг пишется в БД, поэтому переживает перезапуск контейнера.
     */
    private void handleStopCommand(long chatId, String userIdStr, List<String> adminIds) {
        if (userIdStr == null || !adminIds.contains(userIdStr)) {
            return;
        }
        DatabaseManager db = AppContext.getDatabaseManager();
        String now = java.time.OffsetDateTime.now().toString();
        boolean ok = db.setSetting(ProcurementJob.PAUSE_KEY, "true")
                && db.setSetting(ProcurementJob.PAUSE_SINCE_KEY, now)
                && db.setSetting(ProcurementJob.PAUSE_BY_KEY, userIdStr);
        if (ok) {
            sendMessageWithRetry(chatId, "⏸ Парсинг ОСТАНОВЛЕН." + "\n\n"
                    + "Плановые прогоны пропускаются, лоты не публикуются." + "\n"
                    + "Возобновить: /resume");
            log.warn("Парсинг поставлен на паузу админом {}", userIdStr);
        } else {
            sendMessageWithRetry(chatId, "⛔ Не удалось сохранить паузу в базе — проверьте место на диске.");
            log.error("Не удалось сохранить признак паузы");
        }
    }

    /** /resume — снимает паузу парсинга. */
    private void handleResumeCommand(long chatId, String userIdStr, List<String> adminIds) {
        if (userIdStr == null || !adminIds.contains(userIdStr)) {
            return;
        }
        DatabaseManager db = AppContext.getDatabaseManager();
        if (db.setSetting(ProcurementJob.PAUSE_KEY, "false")) {
            sendMessageWithRetry(chatId, "▶ Парсинг ВОЗОБНОВЛЁН. Ближайший плановый прогон отработает штатно.");
            log.warn("Пауза парсинга снята админом {}", userIdStr);
        } else {
            sendMessageWithRetry(chatId, "⛔ Не удалось снять паузу — проверьте место на диске.");
        }
    }

    /** /status — краткое состояние: пауза, место на диске, база, расписание. */
    /**
     * Разовая сверка статусов уже опубликованных лотов с карточками torgi.
     * До 07.09.2026 статус брался из описания RSS, где вместо него приходило эхо фильтра
     * запроса, и всем лотам подряд проставлялось «Не состоялся». Команда проходит по лотам
     * с завершённым статусом, спрашивает настоящий и правит и базу, и сообщения в канале.
     */
    /**
     * Проверка связки с НСПД по кадастровому номеру: /geocode 91:02:001017:957
     * Нужна, чтобы убедиться в работоспособности геокодера, не дожидаясь публикации лота —
     * новые подходящие лоты появляются далеко не каждый день.
     */
    private void handleGeocodeCommand(long chatId, String text, String userIdStr, List<String> adminIds) {
        if (userIdStr == null || !adminIds.contains(userIdStr)) {
            return;
        }
        String argument = text.replaceAll("(?i)^/geocode(@sevnto_bot)?", "").trim();
        if (argument.isEmpty()) {
            sendMessageWithRetry(chatId, "Укажите кадастровый номер: /geocode 91:02:001017:957");
            return;
        }
        DatabaseManager db = AppContext.getDatabaseManager();
        boolean wasCached = db != null && db.getCadastralPoint(argument) != null;

        long startedAt = System.currentTimeMillis();
        CadastralGeocoder.Point point = CadastralGeocoder.resolve(argument);
        long elapsed = System.currentTimeMillis() - startedAt;

        if (point == null) {
            sendMessageWithRetry(chatId, "❌ НСПД не вернул координат по " + argument
                    + " (за " + elapsed + " мс). Подробности в логах.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("✅ ").append(argument).append("\n")
          .append("Координаты: ").append(point.getLat()).append(", ").append(point.getLon()).append("\n");
        if (point.getAddress() != null) {
            sb.append("Адрес ЕГРН: ").append(point.getAddress()).append("\n");
        }
        sb.append("Источник: ").append(wasCached ? "кэш БД" : "запрос к НСПД")
          .append(" (").append(elapsed).append(" мс)").append("\n")
          .append(createYandexMapsPointLink(point.getLat(), point.getLon()));
        sendMessageWithRetry(chatId, sb.toString());
    }

    private void handleRecheckStatusCommand(long chatId, String userIdStr, List<String> adminIds) {
        if (userIdStr == null || !adminIds.contains(userIdStr)) {
            return;
        }
        // Выполняем синхронно, как остальные админ-команды: проход по нескольким десяткам
        // лотов занимает около минуты.
        DatabaseManager db = AppContext.getDatabaseManager();
        {
            List<String> numbers = db.getSentLotsWithFinalStatus();
            sendMessageWithRetry(chatId, "🔍 Сверяю статусы " + numbers.size() + " лотов с карточками torgi...");

            int fixed = 0;
            int confirmed = 0;
            int unavailable = 0;
            StringBuilder changes = new StringBuilder();
            for (String number : numbers) {
                Procurement lot = db.getProcurementByNumber(number);
                if (lot == null) {
                    continue;
                }
                String realStatus = CompletedLotsParser.fetchStatusFromApi(number);
                if (realStatus == null) {
                    unavailable++;
                    continue;
                }
                if (realStatus.equals(lot.getLotStatus())) {
                    confirmed++;
                    continue;
                }
                db.updateLotStatus(number, realStatus);
                Procurement updated = db.getProcurementByNumber(number);
                for (DatabaseManager.MessageMapping mapping : db.getMessageMappings(number)) {
                    try {
                        updateProcurementMessage(mapping.chatId, mapping.messageId, updated);
                    } catch (Exception e) {
                        log.error("Не удалось поправить сообщение {} для лота {}: {}",
                                mapping.messageId, number, e.getMessage());
                    }
                }
                fixed++;
                if (changes.length() < 3000) {
                    changes.append("• ").append(number).append(": ")
                           .append(CompletedLotsParser.getStatusDisplayName(lot.getLotStatus()))
                           .append(" → ").append(CompletedLotsParser.getStatusDisplayName(realStatus)).append("\n");
                }
                // torgi не любит частых обращений — идём неспешно
                try {
                    Thread.sleep(700);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            StringBuilder result = new StringBuilder();
            result.append("✅ Сверка завершена\n\n")
                  .append("Исправлено: ").append(fixed).append("\n")
                  .append("Подтверждено: ").append(confirmed).append("\n");
            if (unavailable > 0) {
                result.append("Карточка недоступна: ").append(unavailable).append("\n");
            }
            if (changes.length() > 0) {
                result.append("\n").append(changes);
            }
            sendMessageWithRetry(chatId, result.toString());
        }
    }

    private void handleStatusCommand(long chatId, String userIdStr, List<String> adminIds) {
        if (userIdStr == null || !adminIds.contains(userIdStr)) {
            return;
        }
        try {
            DatabaseManager db = AppContext.getDatabaseManager();
            boolean paused = "true".equals(db.getSetting(ProcurementJob.PAUSE_KEY, "false"));
            String since = db.getSetting(ProcurementJob.PAUSE_SINCE_KEY, "");
            long freeMb = db.getFreeDiskSpace() / (1024 * 1024);

            StringBuilder sb = new StringBuilder();
            sb.append(paused ? "⏸ Парсинг: НА ПАУЗЕ" : "▶ Парсинг: работает");
            if (paused && !since.isEmpty()) {
                sb.append(" (с ").append(since, 0, Math.min(16, since.length())).append(")");
            }
            sb.append("\n");
            sb.append("Свободно на диске: ").append(freeMb >= 0 ? freeMb + " МБ" : "неизвестно").append("\n");
            sb.append("Расписание: 10:00 и 17:30 (пн-пт)").append("\n\n");
            sb.append("Лоты в базе: ").append(db.getTotalProcurementsCount()).append("\n");
            sb.append("Ждут публикации: ").append(db.getUnsentProcurementsCount());
            sendMessageWithRetry(chatId, sb.toString());
        } catch (Exception e) {
            log.warn("Ошибка команды /status: {}", e.getMessage());
            sendMessageWithRetry(chatId, "Не удалось собрать статус: " + e.getMessage());
        }
    }

    private void handleFullParseCommand(long chatId, int messageId, String userIdStr, List<String> adminIds) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            sendMessageWithRetry(chatId, "🚀 Запускаю полный парсинг в чат парсинга...");
            int count = parseAndPublishLots(Integer.MAX_VALUE, Config.getParseGroupId(), true, true);
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

    private void handleParseSberCommand(long chatId, int messageId, String userIdStr, List<String> adminIds) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            sendMessageWithRetry(chatId, "🚀 Запускаю тестовый парсинг Сбербанк-АСТ (2 лота)...");
            
            try {
                SberAstParser sberParser = new SberAstParser();
                // Запрашиваем больше лотов (50), чтобы гарантированно найти 2 подходящих
                List<Procurement> allProcurements = sberParser.parse(50, false);
                
                // Берем первые 2 подходящих лота (которые уже прошли фильтры)
                List<Procurement> procurements = allProcurements.stream()
                        .limit(2)
                        .collect(java.util.stream.Collectors.toList());
                
                if (procurements.isEmpty()) {
                    sendMessageWithRetry(chatId, "⚠️ Подходящие лоты не найдены");
                    log.info("SberAst test parse: no suitable lots found (checked {} total)", allProcurements.size());
                } else {
                    // Сохраняем в БД, чтобы /start lot_... и поиск по заголовку работали
                    DatabaseManager db = AppContext.getDatabaseManager();
                    db.saveProcurements(procurements);
                    for (Procurement p : procurements) {
                        sendProcurementMessage(chatId, p);
                        log.info("Sent SberAst test lot: {}", p.getNumber());
                    }
                    sendMessageWithRetry(chatId, "✅ Тестовый парсинг завершён, показано " + procurements.size() + " подходящих лотов из " + allProcurements.size() + " найденных");
                    log.info("SberAst test parse completed, {} suitable lots shown from {} total", procurements.size(), allProcurements.size());
                }
            } catch (Exception e) {
                log.error("Error during SberAst test parsing: {}", e.getMessage(), e);
                sendMessageWithRetry(chatId, "❌ Ошибка при парсинге: " + e.getMessage());
            }
            
            try {
                deleteMessage(chatId, messageId);
                log.info("Deleted /parsesber message {} in chat {}", messageId, chatId);
            } catch (Exception e) {
                log.warn("Failed to delete /parsesber message {} in chat {}: {}", messageId, chatId, e.getMessage());
            }
        } else {
            try {
                deleteMessage(chatId, messageId);
                log.info("Deleted /parsesber message {} in chat {} (not admin)", messageId, chatId);
            } catch (Exception e) {
                log.warn("Failed to delete /parsesber message {} in chat {}: {}", messageId, chatId, e.getMessage());
            }
        }
    }

    private void handleParseBankrotCommand(long chatId, int messageId, String userIdStr, List<String> adminIds) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            sendMessageWithRetry(chatId, "🚀 Запускаю тестовый парсинг ЦДТРФ (банкротные торги, 2 лота)...");
            
            try {
                BankrotCdtrfParser bankrotParser = new BankrotCdtrfParser();
                List<Procurement> procurements = bankrotParser.parse(2, false, false);
                
                if (procurements.isEmpty()) {
                    sendMessageWithRetry(chatId, "⚠️ Лоты не найдены");
                    log.info("BankrotCdtrf test parse: no lots found");
                } else {
                    // Сохраняем в БД, чтобы /start lot_... и поиск по заголовку работали
                    DatabaseManager db = AppContext.getDatabaseManager();
                    db.saveProcurements(procurements);
                    for (Procurement p : procurements) {
                        sendProcurementMessage(chatId, p);
                        log.info("Sent BankrotCdtrf test lot: {}", p.getNumber());
                    }
                    sendMessageWithRetry(chatId, "✅ Тестовый парсинг завершён, показано " + procurements.size() + " лотов");
                    log.info("BankrotCdtrf test parse completed, {} lots shown", procurements.size());
                }
            } catch (Exception e) {
                log.error("Error during BankrotCdtrf test parsing: {}", e.getMessage(), e);
                sendMessageWithRetry(chatId, "❌ Ошибка при парсинге: " + e.getMessage());
            }
            
            try {
                deleteMessage(chatId, messageId);
                log.info("Deleted /parsebankrot message {} in chat {}", messageId, chatId);
            } catch (Exception e) {
                log.warn("Failed to delete /parsebankrot message {} in chat {}: {}", messageId, chatId, e.getMessage());
            }
        } else {
            try {
                deleteMessage(chatId, messageId);
                log.info("Deleted /parsebankrot message {} in chat {} (not admin)", messageId, chatId);
            } catch (Exception e) {
                log.warn("Failed to delete /parsebankrot message {} in chat {}: {}", messageId, chatId, e.getMessage());
            }
        }
    }

    /**
     * Очищает и оптимизирует адрес для поиска на Яндекс.Картах.
     * Удаляет лишние детали и оставляет только основные элементы: город-улица-дом.
     */
    public static String cleanAddress(String address) {
        if (address == null) return null;

        // Удаляем лишние пробелы и приводим к нормальному виду
        String cleaned = address.trim().replaceAll("\\s+", " ");

        // Удаляем внутригородские территориальные единицы вида "вн.тер.г. Гагаринский муниципальный округ"
        // Они сбивают геокодер: "Гагаринский" матчится на г. Гагарин Смоленской области вместо Севастополя
        cleaned = cleaned.replaceAll(",?\\s*вн\\.тер\\.г\\.\\s*[^,]+", "");
        // Убираем двойные запятые после удаления
        cleaned = cleaned.replaceAll(",\\s*,", ",").replaceAll("^\\s*,\\s*", "").trim();

        // Удаляем детали, которые не нужны для поиска
        String[] stopWords = {
            "кв.", "квартира", "литер", "лит.", "строение", "стр.", "корпус", "корп.",
            "помещение", "офис", "подъезд", "этаж", "комната", "кабинет"
        };
        
        String lower = cleaned.toLowerCase();
        int minIdx = cleaned.length();
        for (String word : stopWords) {
            int idx = lower.indexOf(word);
            if (idx > 0 && idx < minIdx) {
                minIdx = idx;
            }
        }
        if (minIdx < cleaned.length()) {
            cleaned = cleaned.substring(0, minIdx).trim();
        }
        
        return cleaned;
    }

    /**
     * Создает оптимизированный адрес для поиска на Яндекс.Картах.
     * Обрабатывает различные типы адресов: обычные адреса, СНТ/ТСН, кадастровые номера.
     */
    public static String createOptimizedAddress(String address, String cadastralNumber) {
        if (address == null) return null;
        
        String cleanedAddress = cleanAddress(address);
        String lowerAddress = cleanedAddress.toLowerCase();
        
        // Проверяем, содержит ли адрес СНТ, ТСН или другие товарищества
        if (containsGardenPartnership(lowerAddress)) {
            // Для СНТ/ТСН используем кадастровый номер, если он есть и валиден
            if (cadastralNumber != null && !cadastralNumber.trim().isEmpty() && isValidCadastralNumber(cadastralNumber)) {
                String addressByCadastral = getAddressByCadastralNumber(cadastralNumber);
                if (addressByCadastral != null) {
                    return addressByCadastral;
                }
            }
            // Если кадастрового номера нет или не удалось получить адрес, извлекаем город и название товарищества
            return extractCityAndPartnership(cleanedAddress);
        }
        
        // Для обычных адресов извлекаем город-улица-дом
        return extractCityStreetHouse(cleanedAddress);
    }

    /**
     * Проверяет, содержит ли адрес упоминание садового товарищества
     */
    private static boolean containsGardenPartnership(String address) {
        String[] partnershipKeywords = {
            "снт", "садовое некоммерческое товарищество", "садоводческое некоммерческое товарищество",
            "тсн", "товарищество собственников недвижимости", "днт", "дачное некоммерческое товарищество",
            "дпк", "дачный потребительский кооператив", "спк", "садоводческий потребительский кооператив"
        };
        
        for (String keyword : partnershipKeywords) {
            if (address.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Извлекает город и название товарищества из адреса СНТ/ТСН
     */
    private static String extractCityAndPartnership(String address) {
        // Ищем город (обычно в начале адреса)
        String[] cityPatterns = {
            "г\\.\\s*([^,]+)", // г. Город
            "город\\s+([^,]+)", // город Город
            "^([^,]+?)\\s*," // Первая часть до запятой
        };
        
        String city = null;
        for (String pattern : cityPatterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(address);
            if (m.find()) {
                city = m.group(1).trim();
                break;
            }
        }
        
        // Ищем название товарищества
        String partnership = null;
        String[] partnershipPatterns = {
            "снт\\s+([^,]+)", // СНТ Название
            "тсн\\s+([^,]+)", // ТСН Название
            "садовое некоммерческое товарищество\\s+([^,]+)", // полное название
            "товарищество собственников недвижимости\\s+([^,]+)" // полное название
        };
        
        for (String pattern : partnershipPatterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(address);
            if (m.find()) {
                partnership = m.group(1).trim();
                break;
            }
        }
        
        // Формируем результат
        if (city != null && partnership != null) {
            return city + ", " + partnership;
        } else if (city != null) {
            return city;
        } else if (partnership != null) {
            return partnership;
        }
        
        return address; // Возвращаем исходный адрес, если не удалось извлечь
    }

    /**
     * Извлекает город-улица-дом из обычного адреса
     */
    private static String extractCityStreetHouse(String address) {
        // Удаляем лишние части адреса, оставляя только основные элементы
        String[] removePatterns = {
            "\\s*,\\s*россия\\s*$", // удаляем ", Россия" в конце
            "\\s*,\\s*рф\\s*$", // удаляем ", РФ" в конце
            "\\s*,\\s*российская федерация\\s*$" // удаляем ", Российская Федерация" в конце
        };
        
        String result = address;
        for (String pattern : removePatterns) {
            result = result.replaceAll(pattern, "");
        }
        
        return result.trim();
    }

    /**
     * Получает адрес по кадастровому номеру.
     * TODO: Реализовать интеграцию с API Росреестра или другими сервисами
     */
    private static String getAddressByCadastralNumber(String cadastralNumber) {
        if (cadastralNumber == null || cadastralNumber.trim().isEmpty()) {
            return null;
        }
        
        // Очищаем кадастровый номер от лишних символов
        String cleanCadastral = cadastralNumber.trim().replaceAll("[^\\d:]", "");
        
        try {
            // TODO: Здесь будет интеграция с API Росреестра
            // Пока возвращаем кадастровый номер как есть для поиска
            return "кадастровый номер " + cleanCadastral;
            
            // Пример будущей реализации:
            // String apiUrl = "https://rosreestr.gov.ru/api/addresses/" + cleanCadastral;
            // HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            // conn.setRequestMethod("GET");
            // conn.setRequestProperty("User-Agent", "Mozilla/5.0...");
            // 
            // if (conn.getResponseCode() == 200) {
            //     // Парсим JSON ответ и извлекаем адрес
            //     return parseAddressFromResponse(conn.getInputStream());
            // }
            
        } catch (Exception e) {
            log.warn("Ошибка при получении адреса по кадастровому номеру {}: {}", cadastralNumber, e.getMessage());
        }
        
        return null;
    }

    /**
     * Проверяет, является ли строка валидным кадастровым номером
     */
    private static boolean isValidCadastralNumber(String cadastralNumber) {
        if (cadastralNumber == null || cadastralNumber.trim().isEmpty()) {
            return false;
        }
        
        // Формат кадастрового номера: XX:XX:XXXXXXX:XXX
        String pattern = "^\\d{2}:\\d{2}:\\d{6,7}:\\d+$";
        return cadastralNumber.trim().matches(pattern);
    }

    private String shortenWithClck(String longUrl) {
        try {
            String api = "https://clck.ru/--?url=" + java.net.URLEncoder.encode(longUrl, java.nio.charset.StandardCharsets.UTF_8.name());
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(api).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            // Добавляем заголовки для лучшей совместимости
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
            
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String shortUrl = br.readLine();
                if (shortUrl != null && shortUrl.startsWith("http")) {
                    return shortUrl.trim();
                }
            }
            // Повторная попытка
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String shortUrl = br.readLine();
                if (shortUrl != null && shortUrl.startsWith("http")) {
                    return shortUrl.trim();
                }
            } catch (Exception ignore) {}
        } catch (Exception ignore) {
        }
        return longUrl; // фолбэк, если укоротить не удалось
    }

    /**
     * Создает короткую ссылку clck.ru из длинной ссылки Яндекс.Карт
     */
    private String createYandexMapsShortLink(String address) {
        try {
            String encodedAddress = java.net.URLEncoder.encode(address, java.nio.charset.StandardCharsets.UTF_8);
            // ll и spn привязывают геокодер к Севастополю — иначе "Гагаринский" матчится на Смоленскую область
            String yandexUrl = "https://yandex.ru/maps/?text=" + encodedAddress
                    + "&ll=33.5254%2C44.6054&spn=0.5%2C0.5";
            return shortenWithClck(yandexUrl);
        } catch (Exception e) {
            log.warn("Ошибка при создании короткой ссылки для адреса {}: {}", address, e.getMessage());
            return null;
        }
    }

    /**
     * Создает короткую ссылку на Яндекс.Карты с точной меткой по координатам (WGS84).
     * Яндекс использует порядок параметров lon,lat.
     */
    private String createYandexMapsPointLink(double lat, double lon) {
        try {
            String yandexUrl = "https://yandex.ru/maps/?ll=" + lon + "%2C" + lat
                    + "&z=17&pt=" + lon + "%2C" + lat;
            return shortenWithClck(yandexUrl);
        } catch (Exception e) {
            log.warn("Ошибка при создании ссылки по координатам {},{}: {}", lat, lon, e.getMessage());
            return null;
        }
    }

    /**
     * Тестовая команда для проверки обновления статуса лота
     * Берет первый лот из второй RSS-ленты, публикует его, затем обновляет статус
     */
    private void handleTestStatusCommand(long chatId, int messageId, String userIdStr, List<String> adminIds) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            sendMessageWithRetry(chatId, "🧪 Запускаю тест обновления статуса...");

            try {
                // Парсим завершенные лоты
                String completedLotsUrl = "https://torgi.gov.ru/new/api/public/lotcards/rss?dynSubjRF=80&lotStatus=SUCCEED,FAILED,CANCELED,APPLICATIONS_SUBMISSION_SUSPENDED&matchPhrase=false&byFirstVersion=true";
                CompletedLotsParser completedParser = new CompletedLotsParser(completedLotsUrl);
                java.util.Map<String, String> completedStatuses = completedParser.parseCompletedLots();

                if (completedStatuses.isEmpty()) {
                    sendMessageWithRetry(chatId, "❌ Не найдены завершенные лоты во второй RSS-ленте");
                    return;
                }

                // Берем первый лот
                String testLotNumber = completedStatuses.keySet().iterator().next();
                String testLotStatus = completedStatuses.get(testLotNumber);

                sendMessageWithRetry(chatId, "📝 Найден тестовый лот: " + testLotNumber + " со статусом: " + testLotStatus);

                // Парсим активную RSS-ленту чтобы получить полные данные о лоте
                ParsingSource source = new ParsingSource("Torgi.gov.ru (Севастополь)", Config.getRssUrl());
                RssParser parser = new RssParser(source);
                List<Procurement> activeLots = parser.parseUntilEnough(100, false);

                // Ищем наш тестовый лот в активных
                Procurement testLot = null;
                for (Procurement p : activeLots) {
                    if (p.getNumber().equals(testLotNumber)) {
                        testLot = p;
                        break;
                    }
                }

                if (testLot == null) {
                    sendMessageWithRetry(chatId, "⚠️ Лот не найден в активной RSS-ленте. Создаю минимальный объект для теста...");
                    testLot = Procurement.builder()
                            .number(testLotNumber)
                            .title("Тестовый лот для проверки обновления статуса")
                            .link("https://torgi.gov.ru/new/public/lots/lot/" + testLotNumber)
                            .lotType("Аукцион")
                            .address("г. Севастополь")
                            .deadline("01-12-2025")
                            .source("Torgi.gov.ru (Севастополь)")
                            .lotStatus("ACTIVE")
                            .imageUrls(new ArrayList<>())
                            .build();
                }

                // Сохраняем в БД как активный
                DatabaseManager db = AppContext.getDatabaseManager();
                testLot.setLotStatus("ACTIVE");
                db.saveProcurements(List.of(testLot));

                // Публикуем лот
                sendMessageWithRetry(chatId, "📤 Публикую тестовый лот...");
                sendProcurementMessage(chatId, testLot);
                db.markAsSent(testLotNumber);

                // Ждем 2 секунды
                Thread.sleep(2000);

                // Обновляем статус
                sendMessageWithRetry(chatId, "🔄 Обновляю статус лота на: " + CompletedLotsParser.getStatusDisplayName(testLotStatus));
                db.updateLotStatus(testLotNumber, testLotStatus);

                // Получаем обновленный лот
                Procurement updatedLot = db.getProcurementByNumber(testLotNumber);

                // Обновляем все связанные сообщения
                List<DatabaseManager.MessageMapping> mappings = db.getMessageMappings(testLotNumber);
                for (DatabaseManager.MessageMapping mapping : mappings) {
                    boolean updated = updateProcurementMessage(mapping.chatId, mapping.messageId, updatedLot);
                    if (updated) {
                        sendMessageWithRetry(chatId, "✅ Сообщение успешно обновлено!");
                    } else {
                        sendMessageWithRetry(chatId, "⚠️ Сообщение не изменилось или произошла ошибка");
                    }
                }

                sendMessageWithRetry(chatId, "✅ Тест завершен! Проверьте обновленное сообщение выше.");

            } catch (Exception e) {
                log.error("Error during test status command: {}", e.getMessage(), e);
                sendMessageWithRetry(chatId, "❌ Ошибка при выполнении теста: " + e.getMessage());
            }

            try {
                deleteMessage(chatId, messageId);
            } catch (Exception e) {
                log.warn("Failed to delete /teststatus message: {}", e.getMessage());
            }
        } else {
            try {
                deleteMessage(chatId, messageId);
            } catch (Exception e) {
                log.warn("Failed to delete /teststatus message: {}", e.getMessage());
            }
        }
    }

    /**
     * Тестовая команда для проверки обновления deadline
     * Автоматически берет первый активный лот и обновляет его deadline на текущая_дата + 7 дней
     */
    private void handleTestDeadlineCommand(long chatId, int messageId, String commandText, String userIdStr, List<String> adminIds) {
        if (userIdStr != null && adminIds.contains(userIdStr)) {
            try {
                sendMessageWithRetry(chatId, "🧪 Запускаю тест обновления deadline...");

                // Получаем первый активный опубликованный лот из БД
                DatabaseManager db = AppContext.getDatabaseManager();
                List<Procurement> activeLots = db.getActiveSentProcurements();

                if (activeLots.isEmpty()) {
                    sendMessageWithRetry(chatId, "❌ В БД нет активных опубликованных лотов для тестирования");
                    deleteMessage(chatId, messageId);
                    return;
                }

                // Берем первый лот
                Procurement lot = activeLots.get(0);
                String lotNumber = lot.getNumber();

                // Генерируем новую дату (текущая дата + 7 дней)
                java.time.LocalDate newDate = java.time.LocalDate.now().plusDays(7);
                java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy");
                String newDeadline = newDate.format(formatter);

                String oldDeadline = lot.getDeadline();
                sendMessageWithRetry(chatId, "📝 Найден лот:\n" + lot.getTitle() + "\n\nСтарый deadline: " + oldDeadline + "\nНовый deadline: " + newDeadline);

                // Обновляем deadline в БД
                db.updateDeadline(lotNumber, newDeadline);

                // Получаем обновленный лот
                Procurement updatedLot = db.getProcurementByNumber(lotNumber);

                // Обновляем все связанные сообщения
                List<DatabaseManager.MessageMapping> mappings = db.getMessageMappings(lotNumber);

                if (mappings.isEmpty()) {
                    sendMessageWithRetry(chatId, "⚠️ Не найдено опубликованных сообщений для этого лота");
                } else {
                    for (DatabaseManager.MessageMapping mapping : mappings) {
                        boolean updated = updateProcurementMessage(mapping.chatId, mapping.messageId, updatedLot);
                        if (updated) {
                            sendMessageWithRetry(chatId, "✅ Сообщение " + mapping.messageId + " в чате " + mapping.chatId + " успешно обновлено!");
                        } else {
                            sendMessageWithRetry(chatId, "⚠️ Сообщение " + mapping.messageId + " не изменилось");
                        }
                    }
                }

                // Отправляем уведомление как при реальном обновлении
                StringBuilder notification = new StringBuilder();
                notification.append("⚠️ <b>Изменение срока подачи заявок (ТЕСТ)</b>\n\n");
                notification.append("Лот: ").append(lot.getTitle()).append("\n\n");
                notification.append("Старый срок: <s>").append(oldDeadline).append("</s>\n");
                notification.append("Новый срок: <b>").append(newDeadline).append("</b>\n\n");
                notification.append("<a href=\"").append(lot.getLink()).append("\">Перейти к лоту</a>");

                org.telegram.telegrambots.meta.api.methods.send.SendMessage msg =
                    new org.telegram.telegrambots.meta.api.methods.send.SendMessage();
                msg.setChatId(chatId);
                msg.setText(notification.toString());
                msg.setParseMode("HTML");
                execute(msg);

                sendMessageWithRetry(chatId, "✅ Тест завершен!");

            } catch (Exception e) {
                log.error("Error during test deadline command: {}", e.getMessage(), e);
                sendMessageWithRetry(chatId, "❌ Ошибка при выполнении теста: " + e.getMessage());
            }

            try {
                deleteMessage(chatId, messageId);
            } catch (Exception e) {
                log.warn("Failed to delete /testdeadline message: {}", e.getMessage());
            }
        } else {
            try {
                deleteMessage(chatId, messageId);
            } catch (Exception e) {
                log.warn("Failed to delete /testdeadline message: {}", e.getMessage());
            }
        }
    }

    /**
     * Обновляет существующее сообщение о лоте, добавляя статус в начало
     *
     * @param chatId ID чата
     * @param messageId ID сообщения для обновления
     * @param procurement Объект закупки с обновленными данными
     * @return true если обновление прошло успешно
     */
    public boolean updateProcurementMessage(long chatId, int messageId, Procurement procurement) {
        log.info("Updating message {} in chat {} for procurement {}", messageId, chatId, procurement.getNumber());

        // Формируем статус для добавления в начало сообщения
        String statusText = "";
        if (procurement.getLotStatus() != null && !"ACTIVE".equals(procurement.getLotStatus())) {
            String statusDisplay = CompletedLotsParser.getStatusDisplayName(procurement.getLotStatus());
            statusText = "⚠️ <b>Статус: " + statusDisplay + "</b>\n\n";
        }

        // Воссоздаем текст сообщения (копируем логику из sendProcurementMessage)
        String lotType = "";
        String priceLabel = "";
        boolean isCdtrf = procurement.getSource() != null && procurement.getSource().contains("ЦДТРФ");

        if (isCdtrf) {
            lotType = procurement.getLotType() != null ? procurement.getLotType() : "Реализация имущества должников";
            priceLabel = "Цена за договор";
        } else if (procurement.getBiddTypeName() != null && procurement.getBiddTypeName().toLowerCase().contains("реализация имущества должников")) {
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

        StringBuilder header = new StringBuilder();
        header.append(statusText); // Добавляем статус в начало
        header.append("<b>").append(lotType).append("</b>\n\n");
        String originalTitle = procurement.getTitle() != null ? procurement.getTitle() : "";
        StringBuilder details = new StringBuilder();

        boolean isSberAst = procurement.getSource() != null && procurement.getSource().contains("Сбербанк-АСТ");
        boolean isRentalContract = procurement.getContractTypeName() != null &&
            procurement.getContractTypeName().toLowerCase().contains("аренды");

        if (isSberAst && procurement.getMonthlyPrice() != null && procurement.getPrice() != null) {
            String formattedMonthlyPrice = DECIMAL_FORMAT.format(procurement.getMonthlyPrice());
            details.append("💰Аренда в месяц: ").append(formattedMonthlyPrice).append(" ₽\n");
            String formattedYearlyPrice = DECIMAL_FORMAT.format(procurement.getPrice());
            details.append("💰Аренда в год: ").append(formattedYearlyPrice).append(" ₽\n");
        } else if (procurement.getPrice() != null) {
            String formattedPrice = DECIMAL_FORMAT.format(procurement.getPrice());
            details.append("💰").append(priceLabel).append(": ").append(formattedPrice).append(" ₽\n");
            if (procurement.getMonthlyPrice() != null && priceLabel.contains("год")) {
                String formattedMonthlyPrice = DECIMAL_FORMAT.format(procurement.getMonthlyPrice());
                details.append("💰Аренда в мес: ").append(formattedMonthlyPrice).append(" ₽\n");
            }
        }

        if (procurement.getDeposit() != null) {
            String formattedDeposit = DECIMAL_FORMAT.format(procurement.getDeposit());
            details.append("💰Задаток: ").append(formattedDeposit).append(" ₽\n");
        }
        boolean isDebtor = procurement.getBiddTypeName() != null && procurement.getBiddTypeName().toLowerCase().contains("реализация имущества должников");
        if (!isDebtor && procurement.getDepositRecipientName() != null && !procurement.getDepositRecipientName().isEmpty()) {
            String shortOrg = getShortOrgNameFull(procurement.getDepositRecipientName());
            details.append("🏛Организатор торгов: ").append(shortOrg).append("\n");
        }
        details.append("🧭г Севастополь\n");
        if (procurement.getContractTerm() != null) {
            if (isCdtrf) {
                details.append(procurement.getContractTerm()).append("\n");
            } else if (isRentalContract && isMeaningfulTerm(procurement.getContractTerm())) {
                // Срок договора — только для лотов аренды и только если он осмысленный
                details.append("📅Срок договора: ").append(procurement.getContractTerm()).append("\n");
            }
        }
        if (procurement.getDeadline() != null) {
            String formattedDeadline = procurement.getDeadline();
            try {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(procurement.getDeadline());
                formattedDeadline = odt.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            } catch (Exception ignore) {}
            details.append("⏰Подача до: <u>").append(formattedDeadline).append("</u>\n\n");
        }
        details.append("Заинтересовал лот? <a href=\"https://t.me/").append(getBotUsername()).append("?start=lot_").append(procurement.getNumber()).append("\">Пиши</a> или звони 88007078692");

        String updatedText = assembleWithLimit(header.toString(), originalTitle, details.toString(), TELEGRAM_TEXT_MAX);
        String updatedCaption = assembleWithLimit(header.toString(), originalTitle, details.toString(), TELEGRAM_CAPTION_MAX);

        // Пробуем обновить как caption (если это фото)
        try {
            org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption editCaption =
                new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption();
            editCaption.setChatId(String.valueOf(chatId));
            editCaption.setMessageId(messageId);
            editCaption.setCaption(updatedCaption);
            editCaption.setParseMode("HTML");
            execute(editCaption);
            log.info("Successfully updated message caption for procurement {}", procurement.getNumber());
            return true;
        } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException e) {
            // Если не получилось обновить caption, пробуем обновить как text
            if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                log.info("Message {} was not modified (same content)", messageId);
                return false;
            }
            log.debug("Failed to edit caption (trying text): {}", e.getMessage());
            try {
                org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText editText =
                    new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText();
                editText.setChatId(String.valueOf(chatId));
                editText.setMessageId(messageId);
                editText.setText(updatedText);
                editText.setParseMode("HTML");
                execute(editText);
                log.info("Successfully updated message text for procurement {}", procurement.getNumber());
                return true;
            } catch (org.telegram.telegrambots.meta.exceptions.TelegramApiException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("message is not modified")) {
                    log.info("Message {} was not modified (same content)", messageId);
                    return false;
                }
                log.error("Failed to update message {} for procurement {}: {}",
                         messageId, procurement.getNumber(), ex.getMessage());
                return false;
            }
        }
    }

    /**
     * Отправляет no-match лот админам на ревью со ссылкой, описанием и кнопками ✅/❌.
     */
    public void sendNoMatchLotForReview(Procurement p) {
        StringBuilder sb = new StringBuilder();
        sb.append("❓ <b>Не удалось определить пригодность лота</b>\n\n");
        if (p.getTitle() != null && !p.getTitle().isEmpty()) {
            sb.append("<b>").append(htmlEscape(p.getTitle())).append("</b>\n\n");
        }
        if (p.getLotDescription() != null && !p.getLotDescription().isEmpty()) {
            sb.append("Описание: ").append(htmlEscape(p.getLotDescription())).append("\n");
        }
        if (p.getAddress() != null && !p.getAddress().isEmpty()) {
            sb.append("Адрес: ").append(htmlEscape(p.getAddress())).append("\n");
        }
        String link = p.getLink() != null && !p.getLink().isEmpty()
                ? p.getLink()
                : "https://torgi.gov.ru/new/public/lots/lot/" + p.getNumber() + "/(lotInfo:info)?fromRec=false";
        sb.append("\n<a href=\"").append(link).append("\">Открыть лот на torgi.gov.ru</a>");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        InlineKeyboardButton ok = new InlineKeyboardButton();
        ok.setText("✅ Опубликовать");
        ok.setCallbackData("nm_ok|" + p.getNumber());
        InlineKeyboardButton no = new InlineKeyboardButton();
        no.setText("❌ Отклонить");
        no.setCallbackData("nm_no|" + p.getNumber());
        row.add(ok);
        row.add(no);
        rows.add(row);
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage();
        msg.setChatId(Config.getAdminGroupId());
        msg.setText(sb.toString());
        msg.setParseMode("HTML");
        msg.setDisableWebPagePreview(true);
        msg.setReplyMarkup(markup);
        try {
            executeWithRetry(msg);
        } catch (Exception e) {
            log.error("Не удалось отправить no-match лот {} на ревью: {}", p.getNumber(), e.getMessage());
        }
    }

    /**
     * Обрабатывает решение админа по no-match лоту (кнопки ✅/❌).
     */
    private void handleNoMatchDecision(String callbackData, long chatId, int messageId, Long userId) {
        String[] parts = callbackData.split("\\|", 2);
        if (parts.length != 2) {
            return;
        }
        String action = parts[0];
        String number = parts[1];

        if ("nm_no".equals(action)) {
            clearInlineKeyboard(chatId, messageId);
            sendMessageWithRetry(chatId, "❌ Лот " + number + " отклонён.");
            log.info("No-match лот {} отклонён админом {}", number, userId);
            return;
        }

        // nm_ok → публикуем
        clearInlineKeyboard(chatId, messageId);
        sendMessageWithRetry(chatId, "⏳ Публикую лот " + number + "...");
        try {
            boolean published = AppContext.getProcessingService().publishSingleLotByNumber(number);
            if (published) {
                sendMessageWithRetry(chatId, "✅ Лот " + number + " одобрен и опубликован.");
                log.info("No-match лот {} одобрен и опубликован админом {}", number, userId);
            } else {
                sendMessageWithRetry(chatId, "⚠️ Лот " + number + " не опубликован (не из Севастополя, без фото или ошибка). См. логи.");
            }
        } catch (Exception e) {
            log.error("Ошибка публикации одобренного лота {}: {}", number, e.getMessage(), e);
            sendMessageWithRetry(chatId, "⚠️ Ошибка публикации лота " + number + ": " + e.getMessage());
        }
    }

    /** Убирает inline-кнопки у сообщения (после решения админа). */
    private void clearInlineKeyboard(long chatId, int messageId) {
        try {
            org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup edit =
                    new org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup();
            edit.setChatId(String.valueOf(chatId));
            edit.setMessageId(messageId);
            edit.setReplyMarkup(null);
            execute(edit);
        } catch (Exception e) {
            log.debug("Не удалось убрать кнопки у сообщения {}: {}", messageId, e.getMessage());
        }
    }

    /** Экранирует спецсимволы HTML для Telegram parse_mode=HTML. */
    private static String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Метод для корректного завершения работы бота
     */
    public void shutdown() {
        log.info("Shutting down TelegramBot instance: {}", instanceId);
        try {
            // Останавливаем long polling сессию
            log.info("Stopping bot session...");
            onClosing();

            // Очищаем все внутренние состояния
            userLotMap.clear();
            adminReplyMap.clear();
            adminDeleteLotMap.clear();
            questionMap.clear();
            log.info("Cleared all internal bot states");

            log.info("TelegramBot shutdown completed");
        } catch (Exception e) {
            log.error("Error during bot shutdown: {}", e.getMessage());
        }
    }

}
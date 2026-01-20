package com.example.procurement;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Валидатор для проверки региона лотов.
 * Проверяет, что лоты действительно из Севастополя (код региона 91).
 */
@Slf4j
public class RegionValidator {
    private static final String SEVASTOPOL_PREFIX = "91";
    private static final String SEVASTOPOL_CADASTRAL_PREFIX = "91:";
    private static final int SAMPLE_SIZE = 10; // Проверяем первые 10 лотов

    /**
     * Результат валидации
     */
    public static class ValidationResult {
        public final boolean isValid;
        public final String message;
        public final List<String> wrongRegions;

        public ValidationResult(boolean isValid, String message, List<String> wrongRegions) {
            this.isValid = isValid;
            this.message = message;
            this.wrongRegions = wrongRegions;
        }
    }

    /**
     * Проверяет, что лоты из правильного региона (Севастополь)
     *
     * @param procurements Список лотов для проверки
     * @return Результат валидации
     */
    public ValidationResult validate(List<Procurement> procurements) {
        if (procurements == null || procurements.isEmpty()) {
            log.warn("Empty procurement list received for validation");
            return new ValidationResult(false, "Список лотов пуст", new ArrayList<>());
        }

        // ВАЖНО: Проверяем ВСЕ лоты, а не только первые N
        int totalCount = procurements.size();
        int sevastopolCount = 0;
        int wrongCount = 0;
        List<String> wrongRegions = new ArrayList<>();
        List<String> wrongLotNumbers = new ArrayList<>();

        for (Procurement p : procurements) {
            if (isFromSevastopol(p)) {
                sevastopolCount++;
            } else {
                wrongCount++;
                String region = extractRegion(p);
                if (region != null && !wrongRegions.contains(region)) {
                    wrongRegions.add(region);
                }
                // Сохраняем первые 5 неправильных номеров для логирования
                if (wrongLotNumbers.size() < 5) {
                    wrongLotNumbers.add(p.getNumber());
                }
            }
        }

        // Проверяем процент лотов из Севастополя
        double sevastopolPercentage = (double) sevastopolCount / totalCount * 100;

        // Если менее 80% лотов из Севастополя - валидация провалена
        if (sevastopolPercentage < 80) {
            log.warn("Validation FAILED: Only {}/{} lots ({:.1f}%) from Sevastopol. Wrong regions: {}",
                sevastopolCount, totalCount, sevastopolPercentage, String.join(", ", wrongRegions));

            String message = String.format(
                "Обнаружены лоты из неправильных регионов: %d/%d (%.1f%%). Регионы: %s",
                wrongCount,
                totalCount,
                (double) wrongCount / totalCount * 100,
                wrongRegions.isEmpty() ? "неизвестно" : String.join(", ", wrongRegions)
            );

            return new ValidationResult(false, message, wrongRegions);
        }

        // Валидация прошла
        log.info("Validation PASSED: {}/{} lots ({:.1f}%) from Sevastopol",
            sevastopolCount, totalCount, sevastopolPercentage);

        return new ValidationResult(true,
            String.format("Найдено %d/%d лотов из Севастополя (%.1f%%)", sevastopolCount, totalCount, sevastopolPercentage),
            new ArrayList<>());
    }

    /**
     * Проверяет, относится ли лот к Севастополю
     */
    private boolean isFromSevastopol(Procurement p) {
        // Проверка по префиксу номера лота
        if (p.getNumber() != null && p.getNumber().startsWith(SEVASTOPOL_PREFIX)) {
            return true;
        }

        // Проверка по кадастровому номеру
        if (p.getCadastralNumber() != null && p.getCadastralNumber().startsWith(SEVASTOPOL_CADASTRAL_PREFIX)) {
            return true;
        }

        // Проверка по адресу (если содержит "Севастополь")
        if (p.getAddress() != null && p.getAddress().toLowerCase().contains("севастополь")) {
            return true;
        }

        return false;
    }

    /**
     * Извлекает код региона из лота для логирования
     */
    private String extractRegion(Procurement p) {
        // Пробуем извлечь из номера лота
        if (p.getNumber() != null && p.getNumber().length() >= 2) {
            String prefix = p.getNumber().substring(0, 2);
            if (prefix.matches("\\d{2}")) {
                return "регион-" + prefix;
            }
        }

        // Пробуем извлечь из кадастрового номера
        if (p.getCadastralNumber() != null && p.getCadastralNumber().length() >= 2) {
            String prefix = p.getCadastralNumber().substring(0, 2);
            if (prefix.matches("\\d{2}")) {
                return "регион-" + prefix;
            }
        }

        // Пробуем извлечь из адреса
        if (p.getAddress() != null) {
            String addr = p.getAddress().toLowerCase();
            if (addr.contains("респ ") || addr.contains("обл ") || addr.contains("край ")) {
                // Извлекаем название региона (первые 30 символов адреса)
                String regionName = p.getAddress().substring(0, Math.min(30, p.getAddress().length()));
                return regionName + "...";
            }
        }

        return null;
    }
}

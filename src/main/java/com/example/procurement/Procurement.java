package com.example.procurement;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Procurement {
    @EqualsAndHashCode.Include
    private String number;
    private String title;
    private String link;
    private String lotType;
    private String address;
    private Double price;
    private Double monthlyPrice;
    private Double deposit;
    private String contractTerm;
    private String deadline;
    private String cadastralNumber;
    private Double area;
    private List<String> imageUrls;
    private String biddTypeName;
    private String contractTypeName;
    private String pricePeriod;
    private String depositRecipientName;
    private String source; // Источник парсинга (torgi.gov.ru, sberbank-ast.ru и т.д.)
    private String lotStatus; // Статус лота: ACTIVE, SUCCEED, FAILED, CANCELED, SUSPENDED
    private Double lat; // Широта точки лота (WGS84) из API
    private Double lon; // Долгота точки лота (WGS84) из API
    private String subjectRfCode; // Код субъекта РФ (Севастополь = 92), authoritative для проверки региона
    private String categoryCode; // Код категории объекта (torgi) для фильтрации недвижимости
    private String categoryName; // Человекочитаемое название категории
    private String lotDescription; // Краткое описание объекта из API (для фильтра движимого имущества по описанию)
    private String pointSource; // Откуда взяты lat/lon: cadastral (ЕГРН, точно) или torgi (поле point, у многолотовых бывает общим)

    /** Координаты получены по кадастровому номеру из НСПД — относятся именно к объекту лота. */
    public static final String POINT_SOURCE_CADASTRAL = "cadastral";
    /** Координаты из поля point на torgi.gov.ru — в многолотовых извещениях это адрес организатора. */
    public static final String POINT_SOURCE_TORGI = "torgi";
    /** Точку задал админ вручную, ответив ссылкой на Яндекс.Карты. */
    public static final String POINT_SOURCE_MANUAL = "manual";
}

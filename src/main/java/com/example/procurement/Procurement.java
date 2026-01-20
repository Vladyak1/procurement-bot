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
}

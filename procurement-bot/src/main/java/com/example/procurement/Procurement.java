package com.example.procurement;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Procurement {
    private String number;
    private String title;
    private String link;
    private Double price; // Годовая цена или цена договора
    private Double monthlyPrice; // Месячная цена (только для аренды)
    private Double area; // Площадь в кв.м
    private String cadastralNumber;
    private String address;
    private Double deposit; // Задаток
    private String contractTerm; // Срок договора (только для аренды)
    private String deadline; // Дата окончания подачи заявок
    private String lotType; // Тип лота: "Реализация имущества должников", "Аукцион на право заключения договора аренды" и т.д.
    private List<String> imageUrls; // Ссылки на изображения
    private String biddTypeName; // Тип торгов (например, реализация имущества должников)
    private String contractTypeName; // Тип договора (купли-продажи, аренды)
    private String pricePeriod; // Период для цены (год/месяц)
    private String depositRecipientName; // Организатор торгов
}

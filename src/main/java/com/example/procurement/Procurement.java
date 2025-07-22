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
}

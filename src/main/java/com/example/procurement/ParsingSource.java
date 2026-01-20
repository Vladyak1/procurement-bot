package com.example.procurement;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Класс, представляющий источник для парсинга закупок
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsingSource {
    private String name;
    private String rssUrl;
    private String xhrUrl;
    
    public ParsingSource(String name, String rssUrl) {
        this.name = name;
        this.rssUrl = rssUrl;
        this.xhrUrl = Config.getXhrUrl(); // используем общий XHR URL по умолчанию
    }
}


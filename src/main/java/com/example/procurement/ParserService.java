package com.example.procurement;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class ParserService {
    private final RssParser rssParser = new RssParser();
    private final LotPageParser lotPageParser = new LotPageParser();

    public List<Procurement> parseAndEnrich(int maxCount, boolean notifyAdminOnNoMatch) {
        List<Procurement> procurements = rssParser.parseUntilEnough(maxCount, notifyAdminOnNoMatch);
        for (Procurement p : procurements) {
            lotPageParser.enrichProcurement(p);
        }
        return procurements;
    }
}

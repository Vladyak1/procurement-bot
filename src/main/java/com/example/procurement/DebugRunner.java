package com.example.procurement;

import java.util.List;

public class DebugRunner {
    public static void main(String[] args) {
        System.out.println("=== Starting Debug Runner ===");

        // Test SberAstParser
        /*
         * System.out.println("\n--- Testing SberAstParser ---");
         * try {
         * SberAstParser sberParser = new SberAstParser();
         * List<Procurement> sberLots = sberParser.parse(5, false);
         * System.out.println("SberAst found " + sberLots.size() + " lots.");
         * for (Procurement p : sberLots) {
         * System.out.println("Lot: " + p.getNumber() + " - " + p.getTitle());
         * }
         * } catch (Exception e) {
         * System.err.println("SberAst Error: " + e.getMessage());
         * e.printStackTrace();
         * }
         */

        // Test BankrotCdtrfParser
        System.out.println("\n--- Testing BankrotCdtrfParser ---");
        try {
            BankrotCdtrfParser bankrotParser = new BankrotCdtrfParser();
            // notifyAdminOnNoMatch = false for debugging
            List<Procurement> bankrotLots = bankrotParser.parse(5, false, false);
            System.out.println("BankrotCdtrf found " + bankrotLots.size() + " lots.");
            for (Procurement p : bankrotLots) {
                System.out.println("Lot: " + p.getNumber() + " - " + p.getTitle());
            }
        } catch (Exception e) {
            System.err.println("BankrotCdtrf Error: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\n=== Debug Runner Finished ===");
    }
}

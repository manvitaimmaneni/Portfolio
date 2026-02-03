package com.example.Api_Assets.service;

import com.example.Api_Assets.dto.AssetRecommendation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatService {

    @Autowired
    private RecommendationService recommendationService;

    public String processMessage(String message) {

        if (message == null || message.isBlank()) {
            return "Please enter a valid query like: top 3 assets";
        }

        String msg = message.toLowerCase();

        // Handle "top N assets"
        if (msg.contains("top")) {
            int n = extractNumber(msg, 3);

            List<AssetRecommendation> topAssets =
                    recommendationService.getTopNAssets(n);

            if (topAssets == null || topAssets.isEmpty()) {
                return "No assets found in portfolio.";
            }

            String assetsText = topAssets.stream()
                    .map(a ->
                            a.getSymbol() + " | " +
                                    a.getRiskLevel() + " | " +
                                    a.getProfitPercent() + "%"
                    )
                    .collect(Collectors.joining(" , "));

            return "Top " + n + " assets: " + assetsText;
        }

        // Default help message (NO newlines)
        return "Try queries like: top 3 assets , top 5 assets";
    }

    private int extractNumber(String text, int defaultValue) {
        Pattern pattern = Pattern.compile("\\d+");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group());
        }
        return defaultValue;
    }
}

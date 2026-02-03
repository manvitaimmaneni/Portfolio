package com.example.Api_Assets.service;

import com.example.Api_Assets.dto.AssetRecommendation;
import com.example.Api_Assets.entity.UserAsset;
import com.example.Api_Assets.repository.UserAssetRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RecommendationService {

    @Autowired
    private UserAssetRepository userAssetRepository;

    @Autowired
    private StockService stockService;

    public List<AssetRecommendation> getTopNAssets(int n) {

        List<UserAsset> allAssets = userAssetRepository.findAll();

        if (allAssets.isEmpty()) return List.of();

        // 🔥 GROUP BY SYMBOL (DISTINCT)
        Map<String, List<UserAsset>> grouped =
                allAssets.stream().collect(Collectors.groupingBy(UserAsset::getSymbol));

        List<AssetRecommendation> recommendations = new ArrayList<>();

        for (Map.Entry<String, List<UserAsset>> entry : grouped.entrySet()) {

            String symbol = entry.getKey();
            List<UserAsset> assets = entry.getValue();

            int totalQty = assets.stream().mapToInt(a -> a.getQty()).sum();

            BigDecimal weightedSum = BigDecimal.ZERO;
            for (UserAsset a : assets) {
                weightedSum = weightedSum.add(
                        a.getBuyPrice().multiply(BigDecimal.valueOf(a.getQty()))
                );
            }

            BigDecimal avgBuyPrice = weightedSum.divide(
                    BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP);

            BigDecimal currentPrice;
            try {
                currentPrice = stockService.getCurrentPrice(symbol);
            } catch (Exception e) {
                continue;
            }

            BigDecimal profitPercent = currentPrice.subtract(avgBuyPrice)
                    .divide(avgBuyPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);

            String riskLevel = calculateRisk(profitPercent);

            recommendations.add(
                    new AssetRecommendation(symbol, riskLevel, profitPercent)
            );
        }

        // 🔥 SORT + LIMIT (TOP N DISTINCT)
        return recommendations.stream()
                .sorted(Comparator.comparing(AssetRecommendation::getProfitPercent).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    private String calculateRisk(BigDecimal percent) {
        if (percent.compareTo(BigDecimal.ZERO) < 0) return "HIGH";
        if (percent.compareTo(BigDecimal.valueOf(5)) >= 0) return "LOW";
        return "MEDIUM";
    }
}

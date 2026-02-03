package com.example.Api_Assets.service;

import com.example.Api_Assets.dto.AssetRecommendation;
import com.example.Api_Assets.entity.UserAsset;
import com.example.Api_Assets.repository.UserAssetRepository;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RecommendationService {

    @Getter
    @Autowired
    private UserAssetRepository userAssetRepository;

    @Autowired
    private StockService stockService;

    @Autowired
    private CryptoService cryptoService;

    private final List<String> TOP_MARKET_STOCKS = Arrays.asList(
            "MSFT", "NVDA", "AAPL", "GOOGL", "AMZN", "META", "TSLA", "AVGO",
            "LLY", "JPM", "V", "WMT", "UNH", "MA", "PG"
    );

    private final Map<String, BigDecimal> MARKET_PERFORMANCE = Map.of(
            "NVDA", new BigDecimal("45.2"),
            "MSFT", new BigDecimal("18.7"),
            "TSLA", new BigDecimal("-12.3"),
            "AAPL", new BigDecimal("23.4"),
            "GOOGL", new BigDecimal("15.8"),
            "AMZN", new BigDecimal("12.1"),
            "META", new BigDecimal("28.4"),
            "AVGO", new BigDecimal("35.6")
    );

    // SYMBOL NORMALIZATION MAP
    private final Map<String, String> SYMBOL_MAP = Map.of(
            "bitcoin", "BTC", "btc-usd", "BTC", "BTCUSD", "BTC",
            "ethereum", "ETH", "eth-usd", "ETH", "ETHUSD", "ETH",
            "solana", "SOL", "sol", "SOL"
    );

    private String normalizeSymbol(String symbol) {
        return SYMBOL_MAP.getOrDefault(symbol.toLowerCase(), symbol.toUpperCase());
    }

    // DEDUPLICATE BY NORMALIZED SYMBOL
    private List<AssetRecommendation> deduplicateRecommendations(List<AssetRecommendation> recs) {
        return recs.stream()
                .collect(Collectors.toMap(
                        rec -> normalizeSymbol(rec.getSymbol()),
                        rec -> rec,
                        (existing, replacement) -> existing // Keep first occurrence
                ))
                .values().stream()
                .collect(Collectors.toList());
    }

    public List<AssetRecommendation> getTopNAssets(int n) {
        List<UserAsset> allAssets = userAssetRepository.findAll();
        if (allAssets.isEmpty()) return List.of();

        Map<String, List<UserAsset>> grouped =
                allAssets.stream().collect(Collectors.groupingBy(UserAsset::getSymbol));

        List<AssetRecommendation> recommendations = new ArrayList<>();

        for (Map.Entry<String, List<UserAsset>> entry : grouped.entrySet()) {
            String symbol = normalizeSymbol(entry.getKey());
            List<UserAsset> assets = entry.getValue();

            int totalQty = assets.stream().mapToInt(a -> a.getQty()).sum();
            BigDecimal weightedSum = BigDecimal.ZERO;
            for (UserAsset a : assets) {
                weightedSum = weightedSum.add(a.getBuyPrice().multiply(BigDecimal.valueOf(a.getQty())));
            }

            BigDecimal avgBuyPrice = weightedSum.divide(BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP);
            BigDecimal currentPrice = getCurrentPrice(symbol, assets.get(0).getAssetType());

            BigDecimal profitPercent = currentPrice.subtract(avgBuyPrice)
                    .divide(avgBuyPrice, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);

            String riskLevel = calculateRisk(profitPercent);
            recommendations.add(new AssetRecommendation(symbol, riskLevel, profitPercent));
        }

        if (recommendations.size() < n) {
            int needed = n - recommendations.size();
            recommendations.addAll(getTopMarketAssets(needed));
        }

        recommendations = deduplicateRecommendations(recommendations);
        return recommendations.stream()
                .sorted(Comparator.comparing(AssetRecommendation::getProfitPercent).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    public List<AssetRecommendation> getTopNStocks(int n) {
        List<UserAsset> userStocks = userAssetRepository.findByAssetType("STOCK");
        List<AssetRecommendation> userRecommendations = getTopNByType(userStocks, n);

        List<AssetRecommendation> marketRecommendations = new ArrayList<>();
        if (userRecommendations.size() < n) {
            int needed = n - userRecommendations.size();
            marketRecommendations = getTopMarketStocks(needed);
        }

        List<AssetRecommendation> allRecommendations = new ArrayList<>();
        allRecommendations.addAll(userRecommendations);
        allRecommendations.addAll(marketRecommendations);

        allRecommendations = deduplicateRecommendations(allRecommendations);
        return allRecommendations.stream()
                .sorted(Comparator.comparing(AssetRecommendation::getProfitPercent).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    public List<AssetRecommendation> getTopNCrypto(int n) {
        List<UserAsset> cryptos = userAssetRepository.findByAssetType("CRYPTO");
        List<AssetRecommendation> userCryptoRecs = getTopNByType(cryptos, n);

        if (userCryptoRecs.size() < n) {
            int needed = n - userCryptoRecs.size();
            userCryptoRecs.addAll(getTopMarketCrypto(needed));
        }

        userCryptoRecs = deduplicateRecommendations(userCryptoRecs);
        return userCryptoRecs.stream()
                .sorted(Comparator.comparing(AssetRecommendation::getProfitPercent).reversed())
                .limit(n)
                .collect(Collectors.toList());
    }

    private List<AssetRecommendation> getTopNByType(List<UserAsset> assets, int n) {
        if (assets.isEmpty()) return List.of();

        Map<String, List<UserAsset>> grouped = assets.stream()
                .collect(Collectors.groupingBy(a -> normalizeSymbol(a.getSymbol())));

        return grouped.entrySet().stream()
                .map(this::calculateRecommendation)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private AssetRecommendation calculateRecommendation(Map.Entry<String, List<UserAsset>> entry) {
        String symbol = entry.getKey();
        List<UserAsset> group = entry.getValue();

        int totalQty = group.stream().mapToInt(UserAsset::getQty).sum();
        BigDecimal weightedSum = group.stream()
                .map(a -> a.getBuyPrice().multiply(BigDecimal.valueOf(a.getQty())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgBuyPrice = weightedSum.divide(BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP);
        BigDecimal currentPrice = getCurrentPrice(symbol, group.get(0).getAssetType());

        BigDecimal profitPercent = currentPrice.subtract(avgBuyPrice)
                .divide(avgBuyPrice, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        String riskLevel = calculateRisk(profitPercent);
        return new AssetRecommendation(symbol, riskLevel, profitPercent);
    }

    private BigDecimal getCurrentPrice(String symbol, String type) {
        try {
            if ("STOCK".equalsIgnoreCase(type)) {
                return stockService.getCurrentPrice(symbol);
            } else if ("CRYPTO".equalsIgnoreCase(type)) {
                return cryptoService.getCryptoPrice(symbol);
            }
        } catch (Exception e) {
            // fallback
        }
        return BigDecimal.valueOf(100.0);
    }

    // Market leaders methods (unchanged but with normalization)
    private List<AssetRecommendation> getTopMarketStocks(int count) {
        return TOP_MARKET_STOCKS.stream().limit(count)
                .map(symbol -> {
                    BigDecimal performance = estimateMarketPerformance(symbol);
                    String riskLevel = calculateRisk(performance);
                    return new AssetRecommendation(symbol, riskLevel, performance);
                }).collect(Collectors.toList());
    }

    private List<AssetRecommendation> getTopMarketCrypto(int count) {
        List<String> topCrypto = Arrays.asList("BTC", "ETH", "SOL", "ADA", "XRP");
        return topCrypto.stream().limit(count)
                .map(symbol -> {
                    BigDecimal performance = estimateCryptoPerformance(symbol);
                    String riskLevel = calculateRisk(performance);
                    return new AssetRecommendation(symbol, riskLevel, performance);
                }).collect(Collectors.toList());
    }

    private List<AssetRecommendation> getTopMarketAssets(int count) {
        List<String> topAssets = new ArrayList<>(TOP_MARKET_STOCKS);
        topAssets.addAll(Arrays.asList("BTC", "ETH"));
        Collections.shuffle(topAssets);
        return topAssets.stream().limit(count)
                .map(symbol -> {
                    BigDecimal performance = MARKET_PERFORMANCE.getOrDefault(symbol, BigDecimal.valueOf(8.5));
                    String riskLevel = calculateRisk(performance);
                    return new AssetRecommendation(symbol, riskLevel, performance);
                }).collect(Collectors.toList());
    }

    private BigDecimal estimateMarketPerformance(String symbol) {
        return MARKET_PERFORMANCE.getOrDefault(symbol, BigDecimal.valueOf(8.5));
    }

    private BigDecimal estimateCryptoPerformance(String symbol) {
        Map<String, BigDecimal> cryptoPerf = Map.of(
                "BTC", new BigDecimal("22.1"),
                "ETH", new BigDecimal("15.8"),
                "SOL", new BigDecimal("89.3")
        );
        return cryptoPerf.getOrDefault(symbol, BigDecimal.valueOf(12.0));
    }

    private String calculateRisk(BigDecimal percent) {
        if (percent.compareTo(BigDecimal.ZERO) < 0) return "HIGH";
        if (percent.compareTo(BigDecimal.valueOf(5)) >= 0) return "LOW";
        return "MEDIUM";
    }

    public List<UserAsset> getAllStocks() {
        return userAssetRepository.findByAssetType("STOCK");
    }

    public boolean isStock(String symbol) {
        return !symbol.toUpperCase().matches(".*(BTC|ETH|ADA|SOL|XRP).*");
    }
}

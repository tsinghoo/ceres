package com.blokaly.ceres.orderbook;

import com.blokaly.ceres.common.DecimalNumber;
import com.blokaly.ceres.data.MarketDataIncremental;
import com.blokaly.ceres.data.MarketDataSnapshot;
import com.blokaly.ceres.data.OrderInfo;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.function.BiFunction;

public class PriceAggregatedOrderBook implements OrderBook<OrderInfo>, TopOfBook {

  private static final Logger LOGGER = LoggerFactory.getLogger(PriceAggregatedOrderBook.class);
  private final String symbol;
  private final String key;
  private final NavigableMap<DecimalNumber, PriceAggregatedOrderBook.PriceLevel> bids = Maps.newTreeMap(Comparator.<DecimalNumber>reverseOrder());
  private final NavigableMap<DecimalNumber, PriceAggregatedOrderBook.PriceLevel> asks = Maps.newTreeMap();
  private long lastSequence;

  public PriceAggregatedOrderBook(String symbol, String key) {
    this.symbol = symbol;
    this.key = key;
    this.lastSequence = 0;
  }

  @Override
  public String getSymbol() {
    return symbol;
  }

  @Override
  public long getLastSequence() {
    return lastSequence;
  }

  @Override
  public Collection<? extends Level> getBids() {
    return bids.values();
  }

  @Override
  public Collection<? extends Level> getAsks() {
    return asks.values();
  }

  @Override
  public void clear() {
    bids.clear();
    asks.clear();
    lastSequence = 0;
  }

  public boolean isInitialized() {
    return lastSequence>0;
  }

  @Override
  public void processSnapshot(MarketDataSnapshot<OrderInfo> snapshot) {
    LOGGER.debug("processing snapshot: {}", snapshot);
    clear();
    snapshot.getBids().forEach(this::processUpdate);
    snapshot.getAsks().forEach(this::processUpdate);
    lastSequence = snapshot.getSequence();
  }

  private void processUpdate(OrderInfo order) {
    NavigableMap<DecimalNumber, PriceAggregatedOrderBook.PriceLevel> levels = sidedLevels(order.side());

    levels.compute(order.getPrice(), (price, existing) -> {
      if (existing == null) {
        return new PriceLevel(price, order.getQuantity());
      } else {
        return existing.update(order.getQuantity());
      }
    });

  }

  private NavigableMap<DecimalNumber, PriceAggregatedOrderBook.PriceLevel> sidedLevels(OrderInfo.Side side) {
    if (side == null || side == OrderInfo.Side.UNKNOWN) {
      return null;
    }

    return side == OrderInfo.Side.BUY ? bids : asks;
  }

  private void processDeletion(OrderInfo order) {
    NavigableMap<DecimalNumber, PriceAggregatedOrderBook.PriceLevel> levels = sidedLevels(order.side());
    levels.remove(order.getPrice());

  }

  @Override
  public void processIncrementalUpdate(MarketDataIncremental<OrderInfo> incremental) {
    long sequence = incremental.getSequence();
    if (sequence < lastSequence) {
      return;
    }

    LOGGER.debug("processing market data: {}", incremental);
    switch (incremental.type()) {
      case UPDATE:
        incremental.orderInfos().forEach(this::processUpdate);
        break;
      case DONE:
        incremental.orderInfos().forEach(this::processDeletion);
        break;
      default:
        LOGGER.debug("Unknown type of market data: {}", incremental);
    }

    lastSequence = sequence;
  }

  @Override
  public String getKey() {
    return key;
  }

  @Override
  public Entry[] topOfBids(int depth) {
    Entry[] entries = new Entry[depth];
    int idx = 0;
    for (Map.Entry<DecimalNumber, PriceAggregatedOrderBook.PriceLevel> entry : bids.entrySet()) {
      if (idx >= depth) {
        break;
      }
      entries[idx++] = wrapPriceLevel(entry);
    }
    return entries;
  }

  @Override
  public Entry[] topOfAsks(int depth) {
    Entry[] entries = new Entry[depth];
    int idx = 0;
    for (Map.Entry<DecimalNumber, PriceAggregatedOrderBook.PriceLevel> entry : asks.entrySet()) {
      if (idx >= depth) {
        break;
      }
      entries[idx++] = wrapPriceLevel(entry);
    }
    return entries;
  }

  private Entry wrapPriceLevel(Map.Entry<DecimalNumber, PriceAggregatedOrderBook.PriceLevel> entry) {
    if (entry == null) {
      return null;
    } else {
      PriceAggregatedOrderBook.PriceLevel level = entry.getValue();
      return new Entry(level.getPrice().toString(), level.getQuantity().toString());
    }
  }

  public static final class PriceLevel implements OrderBook.Level {

    private final DecimalNumber price;
    private final DecimalNumber total;


    private PriceLevel(DecimalNumber price, DecimalNumber total) {
      this.price = price;
      this.total = total;
    }

    @Override
    public DecimalNumber getPrice() {
      return price;
    }

    @Override
    public DecimalNumber getQuantity() {
      return total;
    }

    @Override
    public String toString() {
      return "[" + price.toString() + "," + total.toString() + "]";
    }

    public PriceLevel update(DecimalNumber quantity) {
      return new PriceLevel(this.price, this.total.minus(quantity));
    }
  }
}
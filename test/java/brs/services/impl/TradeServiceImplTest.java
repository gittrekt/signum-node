package brs.services.impl;

import static org.junit.Assert.*;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import brs.Trade;
import brs.db.BurstIterator;
import brs.db.sql.EntitySqlTable;
import brs.db.store.TradeStore;
import org.junit.Before;
import org.junit.Test;

public class TradeServiceImplTest {

  private TradeServiceImpl t;

  private TradeStore mockTradeStore;
  private EntitySqlTable<Trade> mockTradeTable;

  @Before
  public void setUp() {
    mockTradeStore = mock(TradeStore.class);
    mockTradeTable = mock(EntitySqlTable.class);

    when(mockTradeStore.getTradeTable()).thenReturn(mockTradeTable);

    t = new TradeServiceImpl(mockTradeStore);
  }

  @Test
  public void getAssetTrades() {
    final long assetId = 123l;
    final int from = 1;
    final int to = 5;

    final BurstIterator<Trade> mockTradesIterator = mock(BurstIterator.class);

    when(mockTradeStore.getAssetTrades(eq(assetId), eq(from), eq(to))).thenReturn(mockTradesIterator);

    assertEquals(mockTradesIterator, t.getAssetTrades(assetId, from, to));
  }

  @Test
  public void getAccountAssetTrades() {
    final long accountId = 12L;
    final long assetId = 123l;
    final int from = 1;
    final int to = 5;

    final BurstIterator<Trade> mockAccountAssetTradesIterator = mock(BurstIterator.class);

    when(mockTradeStore.getAccountAssetTrades(eq(accountId), eq(assetId), eq(from), eq(to))).thenReturn(mockAccountAssetTradesIterator);

    assertEquals(mockAccountAssetTradesIterator, t.getAccountAssetTrades(accountId, assetId, from, to));
  }

  @Test
  public void getAccountTrades() {
    final long accountId = 123l;
    final int from = 1;
    final int to = 5;

    final BurstIterator<Trade> mockTradesIterator = mock(BurstIterator.class);

    when(mockTradeStore.getAccountTrades(eq(accountId), eq(from), eq(to))).thenReturn(mockTradesIterator);

    assertEquals(mockTradesIterator, t.getAccountTrades(accountId, from, to));
  }

  @Test
  public void getCount() {
    final int count = 5;

    when(mockTradeTable.getCount()).thenReturn(count);

    assertEquals(count, t.getCount());
  }
}

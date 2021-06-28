package com.axelor.apps.supplychain.service.stockreservation;

import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.sale.db.SaleOrderLine;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.apps.stock.service.StockLocationLineService;
import com.axelor.apps.supplychain.service.app.AppSupplychainService;
import com.axelor.exception.AxelorException;
import com.google.inject.Inject;
import java.math.BigDecimal;
import java.util.List;

public class SaleOrderLineUpdateReservedQtyServiceImpl implements SaleOrderLineUpdateReservedQtyService {

  protected ReservedQtyFetchService reservedQtyFetchService;
  protected UnitConversionService unitConversionService;
  protected StockMoveLineUpdateReservedQtyService stockMoveLineUpdateReservedQtyService;
  protected AppSupplychainService appSupplychainService;
  protected StockLocationLineService stockLocationLineService;
  protected StockLocationLineUpdateReservedQtyService stockLocationLineUpdateReservedQtyService;

  @Inject
  public SaleOrderLineUpdateReservedQtyServiceImpl(
    ReservedQtyFetchService reservedQtyFetchService,
    UnitConversionService unitConversionService,
    StockMoveLineUpdateReservedQtyService stockMoveLineUpdateReservedQtyService,
    AppSupplychainService appSupplychainService,
    StockLocationLineService stockLocationLineService,
    StockLocationLineUpdateReservedQtyService stockLocationLineUpdateReservedQtyService) {
    this.reservedQtyFetchService = reservedQtyFetchService;
    this.unitConversionService = unitConversionService;
    this.stockMoveLineUpdateReservedQtyService = stockMoveLineUpdateReservedQtyService;
    this.appSupplychainService = appSupplychainService;
    this.stockLocationLineService = stockLocationLineService;
    this.stockLocationLineUpdateReservedQtyService = stockLocationLineUpdateReservedQtyService;
  }

  /**
   * Update reserved qty for sale order line from already updated stock move.
   *
   * @param saleOrderLine
   * @throws AxelorException
   */
  @Override
  public void updateReservedQty(SaleOrderLine saleOrderLine) throws AxelorException {
    // compute from stock move lines
    List<StockMoveLine> stockMoveLineList =
        reservedQtyFetchService.fetchRelatedPlannedStockMoveLineList(saleOrderLine);
    BigDecimal reservedQty = BigDecimal.ZERO;
    for (StockMoveLine stockMoveLine : stockMoveLineList) {
      reservedQty =
          reservedQty.add(
              unitConversionService.convertManagingNullUnit(
                  stockMoveLine.getUnit(),
                  saleOrderLine.getUnit(),
                  stockMoveLine.getReservedQty(),
                  saleOrderLine.getProduct()));
    }
    saleOrderLine.setReservedQty(reservedQty);
  }
}

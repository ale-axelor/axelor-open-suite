package com.axelor.apps.supplychain.service.stockreservation;

import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.sale.db.SaleOrderLine;
import com.axelor.apps.stock.db.StockLocationLine;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.apps.stock.service.StockLocationLineService;
import com.axelor.apps.supplychain.exception.IExceptionMessage;
import com.axelor.apps.supplychain.service.app.AppSupplychainService;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.util.List;

public class SaleOrderLineStockReservationServiceImpl
    implements SaleOrderLineStockReservationService {

  protected ReservedQtyFetchService reservedQtyFetchService;
  protected UnitConversionService unitConversionService;
  protected AppSupplychainService appSupplychainService;
  protected StockLocationLineService stockLocationLineService;
  protected SaleOrderLineUpdateReservedQtyService saleOrderLineUpdateReservedQtyService;
  protected StockMoveLineUpdateReservedQtyService stockMoveLineUpdateReservedQtyService;
  protected StockLocationLineUpdateReservedQtyService stockLocationLineUpdateReservedQtyService;

  @Inject
  public SaleOrderLineStockReservationServiceImpl(
      ReservedQtyFetchService reservedQtyFetchService,
      UnitConversionService unitConversionService,
      AppSupplychainService appSupplychainService,
      StockLocationLineService stockLocationLineService,
      SaleOrderLineUpdateReservedQtyService saleOrderLineUpdateReservedQtyService,
      StockLocationLineUpdateReservedQtyService stockLocationLineUpdateReservedQtyService) {
    this.reservedQtyFetchService = reservedQtyFetchService;
    this.unitConversionService = unitConversionService;
    this.appSupplychainService = appSupplychainService;
    this.stockLocationLineService = stockLocationLineService;
    this.saleOrderLineUpdateReservedQtyService = saleOrderLineUpdateReservedQtyService;
    this.stockLocationLineUpdateReservedQtyService = stockLocationLineUpdateReservedQtyService;
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void requestQty(SaleOrderLine saleOrderLine) throws AxelorException {
    if (saleOrderLine.getProduct() == null || !saleOrderLine.getProduct().getStockManaged()) {
      return;
    }
    saleOrderLine.setIsQtyRequested(true);
    if (saleOrderLine.getQty().signum() < 0) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.SALE_ORDER_LINE_REQUEST_QTY_NEGATIVE));
    }
    StockMoveLine stockMoveLine = reservedQtyFetchService.fetchPlannedStockMoveLine(saleOrderLine);
    if (stockMoveLine != null) {
      stockMoveLine.setIsQtyRequested(true);
    }
    updateRequestedReservedQty(saleOrderLine, saleOrderLine.getQty());
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void cancelReservation(SaleOrderLine saleOrderLine) throws AxelorException {
    if (saleOrderLine.getProduct() == null || !saleOrderLine.getProduct().getStockManaged()) {
      return;
    }
    saleOrderLine.setIsQtyRequested(false);
    StockMoveLine stockMoveLine = reservedQtyFetchService.fetchPlannedStockMoveLine(saleOrderLine);
    if (stockMoveLine != null) {
      stockMoveLine.setIsQtyRequested(false);
    }
    updateRequestedReservedQty(saleOrderLine, saleOrderLine.getDeliveredQty());
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void allocate(SaleOrderLine saleOrderLine) throws AxelorException {
    if (saleOrderLine.getProduct() == null || !saleOrderLine.getProduct().getStockManaged()) {
      return;
    }
    // request the maximum quantity
    requestQty(saleOrderLine);
    StockMoveLine stockMoveLine = reservedQtyFetchService.fetchPlannedStockMoveLine(saleOrderLine);

    if (stockMoveLine == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.SALE_ORDER_LINE_NO_STOCK_MOVE));
    }
    // search for the maximum quantity that can be allocated.
    StockLocationLine stockLocationLine =
        stockLocationLineService.getOrCreateStockLocationLine(
            stockMoveLine.getStockMove().getFromStockLocation(), stockMoveLine.getProduct());
    BigDecimal availableQtyToBeReserved =
        stockLocationLine.getCurrentQty().subtract(stockLocationLine.getReservedQty());
    Product product = stockMoveLine.getProduct();
    BigDecimal availableQtyToBeReservedSaleOrderLine =
        unitConversionService
            .convertManagingNullUnit(
                saleOrderLine.getUnit(),
                stockLocationLine.getUnit(),
                availableQtyToBeReserved,
                product)
            .add(saleOrderLine.getReservedQty());
    BigDecimal qtyThatWillBeAllocated =
        saleOrderLine.getQty().min(availableQtyToBeReservedSaleOrderLine);

    // allocate it
    if (qtyThatWillBeAllocated.compareTo(saleOrderLine.getReservedQty()) > 0) {
      updateReservedQty(saleOrderLine, qtyThatWillBeAllocated);
    }
  }

  @Override
  public void deallocate(SaleOrderLine saleOrderLine) throws AxelorException {
    if (saleOrderLine.getProduct() == null || !saleOrderLine.getProduct().getStockManaged()) {
      return;
    }
    updateReservedQty(saleOrderLine, BigDecimal.ZERO);
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void updateReservedQty(SaleOrderLine saleOrderLine, BigDecimal newReservedQty)
      throws AxelorException {
    if (saleOrderLine.getProduct() == null || !saleOrderLine.getProduct().getStockManaged()) {
      return;
    }
    StockMoveLine stockMoveLine = reservedQtyFetchService.fetchPlannedStockMoveLine(saleOrderLine);

    stockMoveLineUpdateReservedQtyService.checkBeforeUpdatingQties(stockMoveLine, newReservedQty);
    if (appSupplychainService.getAppSupplychain().getBlockDeallocationOnAvailabilityRequest()) {
      stockMoveLineUpdateReservedQtyService.checkAvailabilityRequest(
          stockMoveLine, newReservedQty, false);
    }

    BigDecimal newRequestedReservedQty = newReservedQty.add(saleOrderLine.getDeliveredQty());
    // update requested reserved qty
    if (newRequestedReservedQty.compareTo(saleOrderLine.getRequestedReservedQty()) > 0
        && newReservedQty.compareTo(BigDecimal.ZERO) > 0) {
      updateRequestedReservedQty(saleOrderLine, newReservedQty);
    }

    StockLocationLine stockLocationLine =
        stockLocationLineService.getOrCreateStockLocationLine(
            stockMoveLine.getStockMove().getFromStockLocation(), stockMoveLine.getProduct());
    BigDecimal availableQtyToBeReserved =
        stockLocationLine.getCurrentQty().subtract(stockLocationLine.getReservedQty());
    BigDecimal diffReservedQuantity = newReservedQty.subtract(saleOrderLine.getReservedQty());
    Product product = stockMoveLine.getProduct();
    BigDecimal diffReservedQuantityLocation =
        unitConversionService.convertManagingNullUnit(
            saleOrderLine.getUnit(), stockLocationLine.getUnit(), diffReservedQuantity, product);
    if (availableQtyToBeReserved.compareTo(diffReservedQuantityLocation) < 0) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.SALE_ORDER_LINE_QTY_NOT_AVAILABLE));
    }
    // update in stock move line and sale order line
    updateReservedQuantityInStockMoveLineFromSaleOrderLine(
        saleOrderLine, stockMoveLine.getProduct(), newReservedQty);

    // update in stock location line
    stockLocationLineUpdateReservedQtyService.updateReservedQty(stockLocationLine);
  }

  @Override
  @Transactional(rollbackOn = {Exception.class})
  public void updateRequestedReservedQty(SaleOrderLine saleOrderLine, BigDecimal newReservedQty)
      throws AxelorException {
    if (saleOrderLine.getProduct() == null || !saleOrderLine.getProduct().getStockManaged()) {
      return;
    }

    StockMoveLine stockMoveLine = reservedQtyFetchService.fetchPlannedStockMoveLine(saleOrderLine);

    if (stockMoveLine == null) {
      // only change requested quantity in sale order line
      saleOrderLine.setRequestedReservedQty(newReservedQty);
      return;
    }

    stockMoveLineUpdateReservedQtyService.checkBeforeUpdatingQties(stockMoveLine, newReservedQty);
    if (appSupplychainService.getAppSupplychain().getBlockDeallocationOnAvailabilityRequest()) {
      stockMoveLineUpdateReservedQtyService.checkAvailabilityRequest(
          stockMoveLine, newReservedQty, true);
    }

    BigDecimal diffReservedQuantity =
        newReservedQty.subtract(saleOrderLine.getRequestedReservedQty());

    // update in stock move line and sale order line
    BigDecimal newAllocatedQty =
        updateRequestedReservedQuantityInStockMoveLines(
            saleOrderLine, stockMoveLine.getProduct(), newReservedQty);

    StockLocationLine stockLocationLine =
        stockLocationLineService.getOrCreateStockLocationLine(
            stockMoveLine.getStockMove().getFromStockLocation(), stockMoveLine.getProduct());

    Product product = stockMoveLine.getProduct();
    // update in stock location line
    BigDecimal diffReservedQuantityLocation =
        unitConversionService.convertManagingNullUnit(
            stockMoveLine.getUnit(), stockLocationLine.getUnit(), diffReservedQuantity, product);
    stockLocationLine.setRequestedReservedQty(
        stockLocationLine.getRequestedReservedQty().add(diffReservedQuantityLocation));

    // update reserved qty
    if (newAllocatedQty.compareTo(saleOrderLine.getReservedQty()) < 0) {
      updateReservedQty(saleOrderLine, newAllocatedQty);
    }
  }

  /**
   * Update requested reserved quantity in stock move lines from sale order line. Manage the case of
   * split stock move lines.
   *
   * @param saleOrderLine
   * @param product
   * @param newReservedQty
   * @return the new allocated quantity
   * @throws AxelorException
   */
  protected BigDecimal updateRequestedReservedQuantityInStockMoveLines(
      SaleOrderLine saleOrderLine, Product product, BigDecimal newReservedQty)
      throws AxelorException {
    if (product == null || !product.getStockManaged()) {
      return BigDecimal.ZERO;
    }
    List<StockMoveLine> stockMoveLineList =
        reservedQtyFetchService.fetchRelatedPlannedStockMoveLineList(saleOrderLine);
    BigDecimal deliveredQty = saleOrderLine.getDeliveredQty();
    BigDecimal allocatedRequestedQty = newReservedQty.subtract(deliveredQty);
    if (allocatedRequestedQty.signum() < 0) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(IExceptionMessage.SALE_ORDER_LINE_REQUESTED_QTY_TOO_LOW));
    }
    for (StockMoveLine stockMoveLine : stockMoveLineList) {
      BigDecimal stockMoveRequestedQty =
          unitConversionService.convertManagingNullUnit(
              saleOrderLine.getUnit(), stockMoveLine.getUnit(), allocatedRequestedQty, product);
      BigDecimal requestedQtyInStockMoveLine = stockMoveLine.getQty().min(stockMoveRequestedQty);
      stockMoveLine.setRequestedReservedQty(requestedQtyInStockMoveLine);
      BigDecimal saleOrderRequestedQtyInStockMoveLine =
          unitConversionService.convertManagingNullUnit(
              stockMoveLine.getUnit(),
              saleOrderLine.getUnit(),
              requestedQtyInStockMoveLine,
              product);
      allocatedRequestedQty = allocatedRequestedQty.subtract(saleOrderRequestedQtyInStockMoveLine);
    }
    saleOrderLine.setRequestedReservedQty(newReservedQty.subtract(allocatedRequestedQty));
    return saleOrderLine.getRequestedReservedQty().subtract(deliveredQty);
  }

  /**
   * Update reserved quantity in stock move lines from sale order line. Manage the case of split
   * stock move lines.
   *
   * @param saleOrderLine
   * @param product
   * @param newReservedQty
   * @throws AxelorException
   */
  protected void updateReservedQuantityInStockMoveLineFromSaleOrderLine(
      SaleOrderLine saleOrderLine, Product product, BigDecimal newReservedQty)
      throws AxelorException {
    if (product == null || !product.getStockManaged()) {
      return;
    }

    List<StockMoveLine> stockMoveLineList =
        reservedQtyFetchService.fetchRelatedPlannedStockMoveLineList(saleOrderLine);
    BigDecimal allocatedQty = newReservedQty;
    for (StockMoveLine stockMoveLine : stockMoveLineList) {
      BigDecimal stockMoveAllocatedQty =
          unitConversionService.convertManagingNullUnit(
              saleOrderLine.getUnit(), stockMoveLine.getUnit(), allocatedQty, product);
      BigDecimal reservedQtyInStockMoveLine =
          stockMoveLine.getRequestedReservedQty().min(stockMoveAllocatedQty);
      stockMoveLine.setReservedQty(reservedQtyInStockMoveLine);
      BigDecimal saleOrderReservedQtyInStockMoveLine =
          unitConversionService.convertManagingNullUnit(
              stockMoveLine.getUnit(),
              saleOrderLine.getUnit(),
              reservedQtyInStockMoveLine,
              product);
      allocatedQty = allocatedQty.subtract(saleOrderReservedQtyInStockMoveLine);
    }
    saleOrderLineUpdateReservedQtyService.updateReservedQty(saleOrderLine);
  }
}

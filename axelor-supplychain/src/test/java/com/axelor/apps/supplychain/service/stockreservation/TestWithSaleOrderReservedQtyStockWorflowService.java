package com.axelor.apps.supplychain.service.stockreservation;

import com.axelor.JpaTestModule;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.sale.db.SaleOrderLine;
import com.axelor.apps.stock.db.StockLocationLine;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.apps.stock.db.repo.StockMoveRepository;
import com.axelor.apps.stock.service.StockLocationLineService;
import com.axelor.db.JpaFixture;
import com.axelor.db.JpaSupport;
import com.axelor.exception.AxelorException;
import com.axelor.test.GuiceModules;
import com.axelor.test.GuiceRunner;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

@RunWith(GuiceRunner.class)
@GuiceModules({JpaTestModule.class})
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestWithSaleOrderReservedQtyStockWorflowService extends JpaSupport {

  @Inject private ReservedQtyStockWorkflowServiceImpl reservedQtyService;
  @Inject private StockMoveRepository stockMoveRepository;
  @Inject private StockLocationLineService stockLocationLineService;
  @Inject private JpaFixture fixture;

  private StockMove stockMove;
  private SaleOrderLine saleOrderLine;
  private StockLocationLine stockLocationLine;

  @Before
  @Transactional
  public void setUp() {
    if (all(Company.class).count() == 0) {
      fixture.load("stock-reservation/stock-move-with-sale-order.yml");
    }
    stockMove = null;
    saleOrderLine = null;
    stockLocationLine = null;
  }

  /*
   * Tests
   */

  /**
   * Test a simple allocation: We request reservation for 1 unit with 10 unit available, and the
   * stock move has a linked sale order.
   *
   * @throws AxelorException
   */
  @Test
  public void testSaleOrderUpdateReservedQuantityWhenRequested() throws AxelorException {
    givenStockMoveAndSaleOrder();
    whenPlanningStockMove();
    thenReservedQty(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN);
  }

  /**
   * Test partial allocation: we already requested 3 units, want the reservation for 7 more but
   * there is only 5 units available.
   *
   * @throws AxelorException
   */
  @Test
  public void testSaleOrderPartialReservedQuantityWhenRequested() throws AxelorException {
    givenPartiallyAllocatedStockMove();
    whenPlanningStockMove();
    thenReservedQty(new BigDecimal("8"), new BigDecimal("8"), new BigDecimal("12"));
  }

  @Test
  public void testSaleOrderReservedQuantityWithStocksNotAvailable() throws AxelorException {
    givenStockMoveWithNoQtyAvailable();
    whenPlanningStockMove();
    thenReservedQty(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN);
  }

  @Test
  public void testDeallocationOnRealize() throws AxelorException {
    givenStockMoveToRealize();
    whenRealizingStockMove();
    thenReservedQty(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  @Test
  public void testDeallocationOnCancel() throws AxelorException {
    givenStockMoveToCancel();
    whenCancelingStockMove();
    thenReservedQty(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  @Test
  public void testReservationWithSplitLine() throws AxelorException {
    givenStockMoveWithSplitLine();
    whenPlanningStockMove();
    thenReservedQty(
        new BigDecimal[] {BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE},
        BigDecimal.valueOf(3),
        BigDecimal.valueOf(3));
  }

  @Test
  public void testReservationOnPartialDelivery() throws AxelorException {
    givenStockMovePartToRealize();
    whenRealizingStockMove();
    thenReservedQty(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    thenRequestedReservedQty(BigDecimal.valueOf(4), BigDecimal.TEN, BigDecimal.ZERO);
  }

  private void givenPartiallyAllocatedStockMove() {
    useStockMove("partiallyAllocatedOutStockMove");
  }

  private void givenStockMoveAndSaleOrder() {
    useStockMove("outStockMove");
  }

  private void givenStockMoveWithNoQtyAvailable() {
    useStockMove("stockMoveNoQtyAvailable");
  }

  private void givenStockMoveToRealize() {
    useStockMove("stockMoveToRealize");
  }

  private void givenStockMoveToCancel() {
    useStockMove("stockMoveToCancel");
  }

  private void givenStockMoveWithSplitLine() {
    useStockMove("stockMoveSplitLines");
  }

  private void givenStockMovePartToRealize() {
    useStockMove("stockMovePartToRealize");
  }

  private void useStockMove(String stockMoveName) {
    stockMove = stockMoveRepository.findByName(stockMoveName);
    StockMoveLine stockMoveLine = stockMove.getStockMoveLineList().get(0);
    saleOrderLine = stockMoveLine.getSaleOrderLine();
    stockLocationLine =
        stockLocationLineService.getStockLocationLine(
            stockMove.getFromStockLocation(), stockMoveLine.getProduct());
  }

  private void whenPlanningStockMove() throws AxelorException {
    reservedQtyService.updateReservedQuantity(stockMove, StockMoveRepository.STATUS_PLANNED);
  }

  private void whenRealizingStockMove() throws AxelorException {
    reservedQtyService.updateReservedQuantity(stockMove, StockMoveRepository.STATUS_REALIZED);
  }

  private void whenCancelingStockMove() throws AxelorException {
    reservedQtyService.updateReservedQuantity(stockMove, StockMoveRepository.STATUS_CANCELED);
  }

  private void thenReservedQty(
      BigDecimal stockMoveReservedQty,
      BigDecimal saleOrderLineReservedQty,
      BigDecimal stockLocationLineReservedQty) {
    thenReservedQty(
        new BigDecimal[] {stockMoveReservedQty},
        saleOrderLineReservedQty,
        stockLocationLineReservedQty);
  }

  private void thenReservedQty(
      BigDecimal[] stockMoveReservedQties,
      BigDecimal saleOrderLineReservedQty,
      BigDecimal stockLocationLineReservedQty) {
    for (int i = 0; i < stockMoveReservedQties.length; i++) {
      Assert.assertEquals(
          stockMoveReservedQties[i], stockMove.getStockMoveLineList().get(i).getReservedQty());
    }
    Assert.assertEquals(saleOrderLineReservedQty, saleOrderLine.getReservedQty());
    Assert.assertEquals(stockLocationLineReservedQty, stockLocationLine.getReservedQty());
  }

  private void thenRequestedReservedQty(
      BigDecimal stockMoveRequestedReservedQty,
      BigDecimal saleOrderLineRequestedReservedQty,
      BigDecimal stockLocationLineRequestedReservedQty) {
    thenRequestedReservedQty(
        new BigDecimal[] {stockMoveRequestedReservedQty},
        saleOrderLineRequestedReservedQty,
        stockLocationLineRequestedReservedQty);
  }

  private void thenRequestedReservedQty(
      BigDecimal[] stockMoveRequestedReservedQties,
      BigDecimal saleOrderLineRequestedReservedQty,
      BigDecimal stockLocationLineRequestedReservedQty) {
    for (int i = 0; i < stockMoveRequestedReservedQties.length; i++) {
      Assert.assertEquals(
          stockMoveRequestedReservedQties[i],
          stockMove.getStockMoveLineList().get(i).getRequestedReservedQty());
    }
    Assert.assertEquals(saleOrderLineRequestedReservedQty, saleOrderLine.getRequestedReservedQty());
    Assert.assertEquals(stockLocationLineRequestedReservedQty, stockLocationLine.getRequestedReservedQty());
  }
}

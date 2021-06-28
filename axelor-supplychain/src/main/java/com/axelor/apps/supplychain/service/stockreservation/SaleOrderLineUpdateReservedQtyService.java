package com.axelor.apps.supplychain.service.stockreservation;

import com.axelor.apps.sale.db.SaleOrderLine;
import com.axelor.exception.AxelorException;
import java.math.BigDecimal;

public interface SaleOrderLineUpdateReservedQtyService {

  /**
   * Update reserved qty for sale order line from already updated stock move.
   *
   * @param saleOrderLine
   * @throws AxelorException
   */
  void updateReservedQty(SaleOrderLine saleOrderLine) throws AxelorException;

}

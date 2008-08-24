package com.jbooktrader.platform.trader;

import com.ib.client.*;
import com.jbooktrader.platform.marketdepth.*;
import com.jbooktrader.platform.model.*;
import com.jbooktrader.platform.position.*;
import com.jbooktrader.platform.report.*;
import com.jbooktrader.platform.strategy.*;
import com.jbooktrader.platform.util.*;

import java.util.*;

/**
 * This class acts as a "wrapper" in the IB's API terminology.
 */
public class Trader extends EWrapperAdapter {
    private final Report eventReport;
    private final TraderAssistant traderAssistant;

    public Trader() {
        traderAssistant = new TraderAssistant(this);
        eventReport = Dispatcher.getReporter();
    }

    public TraderAssistant getAssistant() {
        return traderAssistant;
    }

    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {
        try {
            if (key.equalsIgnoreCase("AccountCode")) {
                synchronized (this) {
                    traderAssistant.setAccountCode(value);
                    notifyAll();
                }
            }
        } catch (Throwable t) {
            // Do not allow exceptions come back to the socket -- it will cause disconnects
            eventReport.report(t);
        }
    }

    @Override
    public void updateNewsBulletin(int msgId, int msgType, String message, String origExchange) {
        String newsBulletin = "Msg ID: " + msgId + " Msg Type: " + msgType + " Msg: " + message + " Exchange: " + origExchange;
        eventReport.report(newsBulletin);
    }


    @Override
    public void execDetails(int orderId, Contract contract, Execution execution) {
        try {
            Map<Integer, OpenOrder> openOrders = traderAssistant.getOpenOrders();
            OpenOrder openOrder = openOrders.get(orderId);
            if (openOrder != null) {
                openOrder.add(execution);
                if (openOrder.isFilled()) {
                    Strategy strategy = openOrder.getStrategy();
                    PositionManager positionManager = strategy.getPositionManager();
                    positionManager.update(openOrder);
                    openOrders.remove(orderId);
                }
            }
        } catch (Throwable t) {
            // Do not allow exceptions come back to the socket -- it will cause disconnects
            eventReport.report(t);
        }
    }

    @Override
    public void error(Exception e) {
        eventReport.report(e.toString());
    }

    @Override
    public void error(String error) {
        eventReport.report(error);
    }

    @Override
    public void error(int id, int errorCode, String errorMsg) {
        try {
            String msg = errorCode + ": " + errorMsg;
            eventReport.report(msg);

            if (errorCode == 1100) {// Connectivity between IB and TWS has been lost.
                traderAssistant.setIsConnected(false);
            }

            // handle errors 1101 and 1102
            boolean isConnectivityRestored = (errorCode == 1101 || errorCode == 1102);
            if (isConnectivityRestored) {
                eventReport.report("Checking for executions while TWS was disconnected from the IB server.");
                traderAssistant.requestExecutions();
                traderAssistant.setIsConnected(true);
            }

            if (errorCode == 317) {// Market depth data has been reset
                traderAssistant.getStrategy(id).getMarketBook().reset();
                eventReport.report("Market depth data has been reset.");
            }

            // 200: bad contract
            // 309: market depth requested for more than 3 symbols
            boolean isInvalidRequest = (errorCode == 200 || errorCode == 309);
            if (isInvalidRequest) {
                Dispatcher.fireModelChanged(ModelListener.Event.Error, "IB reported: " + errorMsg);
            }

            boolean requiresNotification = (errorCode != 2104 && errorCode != 2106 && errorCode != 2107 && errorCode != 317);
            if (requiresNotification) {
                SecureMailSender.getInstance().send(msg);
            }
        } catch (Throwable t) {
            // Do not allow exceptions come back to the socket -- it will cause disconnects
            eventReport.report(t);
        }
    }


    @Override
    public void updateMktDepth(int tickerId, int position, int operation, int side, double price, int size) {
        try {
            MarketBook marketBook = traderAssistant.getMarketBook(tickerId);
            marketBook.update(position, MarketBookOperation.getOperation(operation), MarketBookSide.getSide(side), price, size);
        } catch (Throwable t) {
            // Do not allow exceptions come back to the socket -- it will cause disconnects
            eventReport.report(t);
        }
    }


    @Override
    public void tickSize(int tickerId, int tickType, int size) {
        try {
            if (tickType == TickType.VOLUME) {
                MarketBook marketBook = traderAssistant.getMarketBook(tickerId);
                marketBook.update(size);
            }
        } catch (Throwable t) {
            eventReport.report(t);
        }
    }

    @Override
    public void nextValidId(int orderId) {
        traderAssistant.setOrderID(orderId);
    }

}

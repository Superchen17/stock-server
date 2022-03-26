package edu.duke.ece568.server;

import java.security.InvalidAlgorithmParameterException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;

public class StockOrder {

    private HashSet<String> availableStatuses;

    private PostgreJDBC jdbc;
    private int orderId;
    private int accountNumber;
    private String symbol;
    private double amount;
    private double limitPrice;
    private Timestamp issueTime;
    private String status;

    private void updateOrderFromDatabase(PostgreJDBC jdbc, int orderId) throws SQLException{
        String query = "SELECT * FROM STOCK_ORDER WHERE ORDER_ID=" + orderId + ";";
        ResultSet resultSet = this.jdbc.executeQueryStatement(query);
        if(!resultSet.next()){
            throw new IllegalArgumentException("cannot find order with orderId " + orderId);
        }
        
        this.orderId = resultSet.getInt("ORDER_ID");
        this.accountNumber = resultSet.getInt("ACCOUNT_NUMBER");
        this.symbol = resultSet.getString("SYMBOL");
        this.amount = resultSet.getDouble("AMOUNT");
        this.limitPrice = resultSet.getDouble("LIMIT_PRICE");
        this.issueTime = resultSet.getTimestamp("iSSUE_TIME");
        this.status = resultSet.getString("ORDER_STATUS");
    }

    /**
     * creation constructor, create a new stockOrder object in db
     * @param accountNumber
     * @param symbol
     * @param amount
     * @param limitPrice
     * @param issueTime
     */
    public StockOrder(PostgreJDBC jdbc, int accountNumber, String symbol, double amount, double limitPrice){
        this(jdbc, -1, accountNumber, symbol, amount, limitPrice, Timestamp.from(Instant.now()), "OPEN");

        if(amount == 0){
            throw new IllegalArgumentException("cannot create order with amount 0");
        }
        if(limitPrice <= 0){
            throw new IllegalArgumentException("cannot create order with limit price <= 0");
        }
    }

    /**
     * default constructor
     * @param jdbc
     * @param orderId
     * @param accountNumber
     * @param symbol
     * @param amount
     * @param limitPrice
     * @param issueTime
     * @param status
     */
    public StockOrder(PostgreJDBC jdbc, int orderId, int accountNumber, String symbol, 
        double amount, double limitPrice, Timestamp issueTime, String status){
        this.jdbc = jdbc;
        this.orderId = orderId;
        this.accountNumber = accountNumber;
        this.symbol = symbol;
        this.amount = amount;
        this.limitPrice = limitPrice;
        this.issueTime = issueTime;
        this.status = status;

        this.availableStatuses = new HashSet<>();
        this.availableStatuses.add("OPEN");
        this.availableStatuses.add("CANCELLED");
    }

    /**
     * query constructor, fetch an existing stock order object from db
     * @param jdbc
     * @param orderId
     * @throws SQLException
     */
    public StockOrder(PostgreJDBC jdbc, int orderId) throws SQLException{
        this.jdbc = jdbc;
        this.updateOrderFromDatabase(jdbc, orderId);
    }

    public int getOrderId(){
        return this.orderId;
    }

    public double getAmount() throws SQLException{
        this.updateOrderFromDatabase(this.jdbc, this.orderId);
        return this.amount;
    }

    public int getAccountNumber() throws SQLException{
        this.updateOrderFromDatabase(this.jdbc, this.orderId);
        return this.accountNumber;
    }

    public void updateOrderAmount(double offset) throws InvalidAlgorithmParameterException, SQLException{
        if(this.orderId < 0){
            throw new InvalidAlgorithmParameterException("cannot update an order not yet uploaded to database");
        }
        this.updateOrderFromDatabase(this.jdbc, this.orderId);
        double newAmount = this.amount + offset;
        String query = 
            "UPDATE STOCK_ORDER " + 
            "SET AMOUNT=" + newAmount + " " + 
            "WHERE ORDER_ID=" + this.orderId +
            ";";
        
        if(!this.jdbc.executeUpdateStatement(query)){
            throw new InvalidAlgorithmParameterException("cannot update order amount to database");
        }
    }

    /**
     * delete stock order from db
     * @throws InvalidAlgorithmParameterException
     */
    public void deleteFromDb() throws InvalidAlgorithmParameterException{
        if(this.orderId < 0){
            throw new InvalidAlgorithmParameterException("cannot delete an order not yet uploaded to database");
        }
        String query = "DELETE FROM STOCK_ORDER WHERE ORDER_ID=" + this.orderId + ";";
        if(!this.jdbc.executeUpdateStatement(query)){
            throw new InvalidAlgorithmParameterException("cannot delete the stock order from database");
        }
        this.orderId = -1;
    }

    /**
     * archive the current order
     * by creating an executed order in ARCHIVE, and deleting from STOCK_ORDER
     * @throws InvalidAlgorithmParameterException
     * @throws SQLException
     */
    public void archive(double tradePrice) throws InvalidAlgorithmParameterException, SQLException{
        ExecutedOrder executedOrder = new ExecutedOrder(this.jdbc, this.orderId, this.symbol, 
            tradePrice, Math.abs(this.amount), this.issueTime);
        executedOrder.commitToDb();
        this.deleteFromDb();
    }

    public void partialArchive(double tradeAmount, double tradePrice) throws InvalidAlgorithmParameterException, SQLException{
        ExecutedOrder executedOrder = new ExecutedOrder(this.jdbc, this.orderId, this.symbol, 
            tradePrice, Math.abs(tradeAmount), this.issueTime);
        executedOrder.commitToDb();
        this.updateOrderAmount(tradeAmount);
    }

    /**
     * commit the current stock order to database
     * @throws InvalidAlgorithmParameterException
     * @throws SQLException
     */
    public void commitToDb() throws InvalidAlgorithmParameterException, SQLException{
        String query = 
            "WITH TEMP AS ( " + 
                "INSERT INTO STOCK_ORDER (ACCOUNT_NUMBER, SYMBOL, AMOUNT, LIMIT_PRICE, ISSUE_TIME, ORDER_STATUS)" +
                "VALUES(" + accountNumber + ", \'" + symbol + "\', " + 
                    amount + ", " + limitPrice + ", \'" + issueTime + "\', \'" + this.status + "\') " + 
                "RETURNING ORDER_ID" + 
            ")" + 
            "SELECT ORDER_ID FROM TEMP;";

        ResultSet resultSet = this.jdbc.executeQueryStatement(query);
        if(!resultSet.next()){
            throw new InvalidAlgorithmParameterException("cannot create a stock order in database");
        }
        this.orderId = resultSet.getInt("ORDER_ID");
    }

    public void matchOrder(){

    }

    // TODO
    /**
     * match a buy order with sale orders
     * @throws SQLException
     * @throws InvalidAlgorithmParameterException
     */
    public void matchBuyOrder() throws SQLException, InvalidAlgorithmParameterException{
        StockOrder matchedOrder = this.getTop1SaleOrdersForBuyOrder();
        while(matchedOrder != null){
            if(this.amount > Math.abs(matchedOrder.amount)){
                double tradePrice = Math.abs(matchedOrder.amount * matchedOrder.limitPrice);

                Account salerAccount = new Account(this.jdbc, matchedOrder.accountNumber);
                Account buyerAccount = new Account(this.jdbc, this.accountNumber);

                salerAccount.tryAddOrRemoveFromBalance(tradePrice);
                buyerAccount.tryAddOrRemoveFromBalance(-1*tradePrice);

                matchedOrder.archive(tradePrice);
                this.partialArchive(matchedOrder.amount, tradePrice);

                matchedOrder = this.getTop1SaleOrdersForBuyOrder();
            }
            else if(this.amount == Math.abs(matchedOrder.amount)){
                double tradePrice = Math.abs(matchedOrder.amount * matchedOrder.limitPrice);

                matchedOrder.archive(tradePrice);
                this.archive(tradePrice);

                Account salerAccount = new Account(this.jdbc, matchedOrder.accountNumber);
                Account buyerAccount = new Account(this.jdbc, this.accountNumber);

                salerAccount.tryAddOrRemoveFromBalance(tradePrice);
                buyerAccount.tryAddOrRemoveFromBalance(-1*tradePrice);
                
                return;
            }
            else{
                return;
            }
        }
    }

    /**
     * for a given buy order, get the most matched sale order
     * @return
     * @throws SQLException
     */
    protected StockOrder getTop1SaleOrdersForBuyOrder() throws SQLException{
        String query = 
            "SELECT * FROM STOCK_ORDER " +
            "WHERE ACCOUNT_NUMBER <> " + this.accountNumber + " " + 
            "AND SYMBOL=\'" + this.symbol +"\' " + 
            "AND AMOUNT < 0 " + 
            "AND LIMIT_PRICE <= " + this.limitPrice + " " + 
            "AND ORDER_STATUS = \'OPEN\' " + 
            "ORDER BY LIMIT_PRICE DESC, ISSUE_TIME ASC " + 
            "LIMIT 1;";

        ResultSet resultSet = this.jdbc.executeQueryStatement(query);
        if(!resultSet.next()){
            return null;
        }

        StockOrder stockOrder = new StockOrder(jdbc, resultSet.getInt("ORDER_ID"), resultSet.getInt("ACCOUNT_NUMBER"), 
            resultSet.getString("SYMBOL"), resultSet.getDouble("AMOUNT"), resultSet.getDouble("LIMIT_PRICE"), 
            resultSet.getTimestamp("ISSUE_TIME"), resultSet.getString("ORDER_STATUS"));

        return stockOrder;
    } 

    /**
     * equal operator
     */
    @Override
    public boolean equals(Object o){
        if(o.getClass().equals(this.getClass())){
            StockOrder rhs = (StockOrder)o;
            return this.orderId == rhs.orderId &&
                this.accountNumber == rhs.accountNumber && 
                this.symbol.equals(rhs.symbol) &&
                this.amount == rhs.amount &&
                this.limitPrice == rhs.limitPrice &&
                this.issueTime.getTime() == rhs.issueTime.getTime() &&
                this.status.equals(rhs.status);
        } 
        return false;
    } 

    /**
     * toString
     */
    @Override
    public String toString(){
        String toReturn = this.orderId + ", " + this.accountNumber + ", " + this.symbol + ", " + 
            this.amount + ", " + this.limitPrice + ", " + this.issueTime + ", " + this.status;
        return toReturn;
    }
}

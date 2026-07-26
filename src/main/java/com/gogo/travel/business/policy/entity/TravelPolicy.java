package com.gogo.travel.business.policy.entity;

/**
 * @author Hollis
 */
public class TravelPolicy {

    private String userLevel;
    private String destinationCity;
    private String cityTier;

    private String flightClass;
    private double hotelLimit;
    private int hotelStarLimit;
    private double dailyMealLimit;
    private double dailyTransportLimit;
    private String trainSeatClass;
    private double approvalThreshold;
    private int advanceBookingDays;

    public String getUserLevel() {
        return userLevel;
    }

    public void setUserLevel(String userLevel) {
        this.userLevel = userLevel;
    }

    public String getDestinationCity() {
        return destinationCity;
    }

    public void setDestinationCity(String destinationCity) {
        this.destinationCity = destinationCity;
    }

    public String getCityTier() {
        return cityTier;
    }

    public void setCityTier(String cityTier) {
        this.cityTier = cityTier;
    }

    public String getFlightClass() {
        return flightClass;
    }

    public void setFlightClass(String flightClass) {
        this.flightClass = flightClass;
    }

    public double getHotelLimit() {
        return hotelLimit;
    }

    public void setHotelLimit(double hotelLimit) {
        this.hotelLimit = hotelLimit;
    }

    public int getHotelStarLimit() {
        return hotelStarLimit;
    }

    public void setHotelStarLimit(int hotelStarLimit) {
        this.hotelStarLimit = hotelStarLimit;
    }

    public double getDailyMealLimit() {
        return dailyMealLimit;
    }

    public void setDailyMealLimit(double dailyMealLimit) {
        this.dailyMealLimit = dailyMealLimit;
    }

    public double getDailyTransportLimit() {
        return dailyTransportLimit;
    }

    public void setDailyTransportLimit(double dailyTransportLimit) {
        this.dailyTransportLimit = dailyTransportLimit;
    }

    public String getTrainSeatClass() {
        return trainSeatClass;
    }

    public void setTrainSeatClass(String trainSeatClass) {
        this.trainSeatClass = trainSeatClass;
    }

    public double getApprovalThreshold() {
        return approvalThreshold;
    }

    public void setApprovalThreshold(double approvalThreshold) {
        this.approvalThreshold = approvalThreshold;
    }

    public int getAdvanceBookingDays() {
        return advanceBookingDays;
    }

    public void setAdvanceBookingDays(int advanceBookingDays) {
        this.advanceBookingDays = advanceBookingDays;
    }
}

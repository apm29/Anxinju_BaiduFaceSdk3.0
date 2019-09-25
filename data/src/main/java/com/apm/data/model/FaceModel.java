package com.apm.data.model;

public class FaceModel {
    public int id;
    public String personType;
    public  String personPic;
    public String noEnterFlag;
    public String personId;
    public String passcode;
    public  String name;
    public String delFlag;

    @Override
    public String toString() {
        return "FaceModel{" +
                "id=" + id +
                ", personType='" + personType + '\'' +
                ", personPic='" + personPic + '\'' +
                ", noEnterFlag='" + noEnterFlag + '\'' +
                ", personId='" + personId + '\'' +
                ", passcode='" + passcode + '\'' +
                ", name='" + name + '\'' +
                ", delFlag='" + delFlag + '\'' +
                '}';
    }
}

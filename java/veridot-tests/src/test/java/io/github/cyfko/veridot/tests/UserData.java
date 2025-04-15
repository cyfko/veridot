package io.github.cyfko.veridot.tests;

public class UserData {
    private String email;

    // No-argument constructor
    public UserData() {
    }

    public UserData(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}

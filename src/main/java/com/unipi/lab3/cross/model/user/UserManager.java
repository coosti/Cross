package com.unipi.lab3.cross.model.user;

import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * class that manages users in the system, storing them in a concurrent hash map 
 * with username as key and User instance as value,
 * provides all useful operations such as register, login, logout and update credentials
 */

public class UserManager {

    // map username - User
    private ConcurrentHashMap<String, User> users;

    public UserManager () {
        this.users = new ConcurrentHashMap<>();
    }

    public UserManager (ConcurrentHashMap<String, User> users) {
        this.users = users;
    }

    public ConcurrentHashMap<String, User> getUsers () {
        return this.users;
    }

    public User getUser (String username) {
        return this.users.get(username);
    }

    /**
     * register a new user
     * 
     * @param username usernamen of the new user
     * @param password password of the new user
     * @return integer code representing the result of the operation
     */
    public synchronized int register (String username, String password) {
        // error cases

        // invalid password
        if (!isValid(password, 8, 20))
            return 101;

        // invalid username
        if (!isValid(username, 3, 12))
            return 103;
        
        // hash password before storing 
        String hashedPassword = hashPassword(password);
        // create new user
        User user = new User(username, hashedPassword, false);

        // add user if not already exists
        if (this.users.putIfAbsent(username, user) != null)
            // username not available
            return 102;

        // successful registration
        return 100;
    }

    /**
     * update user credentials
     * 
     * @param username username of the user that wants to update credentials
     * @param newPwd new password (unencrypted)
     * @param oldPwd old password (unencrypted)
     * @return integer code representing the result of the operation
     */
    public synchronized int updateCredentials (String username, String newPwd, String oldPwd) {
        // get user instance by username
        User user = this.users.get(username);

        // user not exists
        if (user == null)
            return 105;

        // get current password
        String currentPwd = user.getPassword();

        // invalid new password
        if (!isValid(newPwd, 8, 20))
            return 101;

        // old password mismatch
        if (!hashPassword(oldPwd).equals(currentPwd))
            return 102;

        // new password equals to old one
        if (newPwd.equals(oldPwd))
            return 103;

        // hash new password
        String newHashedPwd = hashPassword(newPwd);
        
        // create new user instance with updated password
        User newUser = new User(username, newHashedPwd, false);
        
        // replace old user with new one
        if (!this.users.replace(username, user, newUser))
            return 105;

        // successful update
        return 100;
    }

    /**
     * login user with given credentials
     * 
     * @param username username of the user that wants to login
     * @param password password of the user that wants to login
     * @return integer code representing the result of the operation
     */
    public synchronized int login (String username, String password) {

        // invalid password
        if (!isValid(password, 8, 20))
            return 103;

        // get user instance by username
        User user = this.users.get(username);

        // user already logged in
        if (user.getLogged())
            return 102;
        
        // get current password
        String currentPwd = user.getPassword();

        // password mismatch
        if (!hashPassword(password).equals(currentPwd))
            return 101;

        // set logged state
        user.setLogged(true);

        // successful login
        return 100;
    }

    /**
     * logout user by username
     * 
     * @param username username of the user that wants to logout
     * @return integer code representing the result of the operation
     */
    public synchronized int logout (String username) {
        // get user instance by username
        User user = this.users.get(username);

        // restore logged state
        user.setLogged(false);

        // successful logout
        return 100;
    }  
    
    /**
     * hash password using SHA-256 algorithm
     * 
     * @param password plaintext password to be hashed
     * @return hashed password
     */
    public String hashPassword(String password) {
        try {
            // create message digest instance for SHA-256
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // converts password string into bytes using UTF-8 encoding and computes its SHA-256 hash
            byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));

            // build hexadecimal string representation of the hash (each byte will be represented by two hexadecimal digits)
            StringBuilder sb = new StringBuilder(digest.length * 2);

            // convert each byte to hexadecimal format
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }

            // return hashed password as hexadecimal string
            return sb.toString();
        } 
        // error if SHA-256 algorithm is not available
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * validate string based on length and allowed characters (alphanumeric only)
     * 
     * @param str string to be validated
     * @param minLen minimum length
     * @param maxLen maximum length
     * @return true if valid, false otherwise
     */
    private boolean isValid (String str, int minLen, int maxLen) {
        // null or empty string
        if (str == null || str.isEmpty())
            return false;

        // check string length
        if (str.length() < minLen || str.length() > maxLen)
            return false;

        // check allowed characters (alphanumeric only)
        for (char c : str.toCharArray()) {
            if (!Character.isLetterOrDigit(c))
                return false;
        }

        return true;
    }
}

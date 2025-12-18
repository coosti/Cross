# CROSS – an exChange oRder bOokS Service

## Project description

CROSS is a Java-based Order Book service simulator developed to model the behaviour of a centralized cryptocurrency exchange.
The project has been realized as final assignment for the Laboratory III module of the Networks course for the academic year 2024/25 and implements a
multithreaded client–server architecture with hybrid TCP/UDP communication.

## Features

CROSS provides a full system to manage buy and sell orders on a simulated market (BTC/USD) through:

- order insertion, cancellation, and execution
- order matching based on price-time priority principle
- TCP request/response communication between system and users
- asynchronous UDP notifications for executed orders
- user management (registration, login, logout)
- automatic data persistence using JSON files
- consultation of price history and daily trading statistics

## Project Structure and Design

CROSS follows a modular client–server design to separate responsibilities:

- **Server side**: manages client connections, order processing, user sessions, persistence, client/server communication via TCP and asynchronous UDP notifications.
- **Client side**: handles user input, request/response communication with TCP and UDP notification management.
- **Model layer**: contains the core business logic, with Order Book, trade management, and price history computation.

The system operates in a concurrent environment, handling multiple clients simultaneously with dedicated threads, thread-safe data structures and synchronization mechanisms.

## Compilation and execution

The project is developed in **Java 17** and uses **Maven** for build automation and dependency management.

### Requirements
- Java 17 or higher
- Maven

### Build 
From the project root directory, run:

mvn clean package

To generate the executable JAR files inside target directory, run:

mvn package

### Compile
To compile the project run:

mvn clean compile

### Server
To exec server main class with JAR file:

java -jar target/cross-server.jar

Alternatively, using maven profile:

mvn exec:java -Pserver

### Client
To exec server main class with JAR file:

java -jar target/cross-client.jar

Alternatively, using maven profile:

mvn exec:java -Pclient
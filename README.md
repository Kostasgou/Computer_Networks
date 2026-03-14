# Java Social Network with Client-Server Architecture

A socket-based social networking platform developed in **Java**, designed to demonstrate core concepts of **computer networks**, **client-server communication**, **concurrent server design**, **file transfer protocols**, and **social graph management**.

This project simulates a complete social media environment where users can register, log in, follow other users, manage profile access, upload and download photos, interact through reposts and comments, and receive activity updates through notifications.

---

## Overview

This project implements a **social networking system** based on a **client-server architecture**.

The platform allows multiple users to connect to a central server and perform social interactions similar to those found in modern social media applications. The system focuses on communication over sockets, concurrency handling on the server side, file-based data persistence, and support for multimedia sharing.

Through this project, the following concepts are combined into a single application:

* **Client-server communication using TCP sockets**
* **Multithreaded request handling**
* **User account management**
* **Social graph representation**
* **Profile access control**
* **Image upload and download**
* **Custom frame-based file transfer**
* **Notification and activity synchronization**
* **Persistent storage using the file system**

The result is a complete academic project that demonstrates both networking and software design principles in a practical and interactive way.

---

## Key Features

### User Management

* User registration
* User login and logout
* Session tracking for active clients
* Account deletion support

### Social Features

* Send follow requests to other users
* Accept, reject, or define follow relationships
* View followers and following lists
* Unfollow users
* Manage access permissions

### Profile and Activity Features

* Access user profiles
* Read profile activity history
* Lock-based profile access control
* Synchronize activity data between server and client
* View notifications and social updates

### Media Features

* Upload photos to the server
* Store multilingual captions for images
* Search photos using stored metadata
* Download photos from the server
* Transfer images using frame-based transmission logic
* Repost existing posts
* Comment on user posts

### System Features

* Multithreaded server with thread pool
* Shared social graph management
* Persistent storage of users and relationships
* File-based profile and media organization
* Support for concurrent access to shared resources

---

## System Architecture

The application follows a **centralized client-server model**.

### Main Components

#### 1. Server

The server is the core of the system and is responsible for:

* Accepting client connections
* Managing logged-in users
* Handling commands from clients
* Storing user data and media
* Maintaining the social graph
* Managing notifications and permissions
* Coordinating file transfer operations

#### 2. Client

The client is a command-line application that allows users to interact with the platform.

The client is responsible for:

* Connecting to the server
* Sending commands and receiving responses
* Displaying menus and interaction options
* Uploading and downloading images
* Synchronizing activity files locally

#### 3. File-Based Storage Layer

The system stores its persistent data directly in the filesystem.

This includes:

* User directories
* Profile files
* Notification/activity files
* Uploaded photos
* Followers and permissions metadata

This design keeps the implementation simple, transparent, and appropriate for academic demonstration.

---

## Core Classes and Responsibilities

### `Server.java`

This is the entry point of the backend.

Main responsibilities:

* Start the server socket
* Initialize required directories
* Load the social graph
* Create a thread pool for concurrent clients
* Accept incoming socket connections
* Assign each client connection to a `ClientHandler`

The server provides the execution environment for all platform operations.

### `ClientHandler.java`

This class contains the main command-processing logic for each connected user.

Responsibilities include:

* Handling signup and login
* Processing follow requests
* Managing profile access
* Uploading and downloading media
* Processing reposts and comments
* Managing notifications
* Enforcing access rules and permissions

This is the most important communication layer between client and server.

### `Client.java`

This is the user-facing client application.

Its responsibilities include:

* Connecting to the server
* Presenting menu-based options
* Sending requests to the server
* Reading server responses
* Managing local copies of synchronized files
* Supporting media upload and download operations

### `SocialGraph.java`

This class represents the social relationships between users.

It manages:

* Followers
* Following
* Extra permissions

It offers operations such as:

* Add user
* Add or remove follow relations
* Retrieve followers/following
* Grant or revoke permissions
* Persist social state to files

### `ProfileManager.java`

This class manages all user-related files stored on the server.

Responsibilities:

* Create user directories
* Create profile and activity files
* Store uploaded photos
* Read profile contents
* Append activity entries
* List and retrieve photo files

It acts as the bridge between application logic and file storage.

### `LockManager.java`

This class provides controlled access to shared profile files.

It is used to:

* Lock profile files when accessed
* Queue waiting clients
* Release locks safely
* Prevent conflicting access to the same profile resource

This adds an important concurrency-control mechanism to the system.

### `Frame.java`

This class represents a unit of file transfer.

It is used during image download operations to:

* Split files into chunks
* Number the chunks with sequence values
* Support ordered transmission
* Enable acknowledgment-based delivery logic

This makes the file transfer process more structured and educational from a networking perspective.

---

## Functional Flow

## 1. Registration and Login

A new user can register through the client application.

During registration:

* A new user ID is created
* A dedicated user directory is generated on the server
* Profile and activity files are initialized
* The user is added to the social graph

During login:

* The server validates the user
* The user session becomes active
* The client gains access to the full command menu

---

## 2. Follow Request Workflow

The system supports a social interaction model based on follow relationships.

A user can:

* Send a follow request to another user
* View incoming requests
* Accept or reject requests
* Create mutual or one-way social connections
* View followers and following lists
* Unfollow a user later

This creates a flexible social graph and allows selective access to profiles and activity.

---

## 3. Profile Access Workflow

Profiles are stored as server-side files and treated as protected resources.

When a client wants to access a profile:

1. A request is sent to the server
2. The server checks access permissions
3. A lock may be acquired for that profile file
4. The profile data is returned to the client
5. The client later releases the profile access

This process demonstrates resource coordination and controlled access in a multi-user environment.

---

## 4. Photo Upload Workflow

Users can upload images along with captions.

The upload process includes:

* Selecting a photo file from the client side
* Sending the photo to the server
* Attaching captions in multiple languages
* Storing the image inside the user’s photo directory
* Updating the user profile with a new post entry
* Informing relevant users through activity updates

This combines media handling with social activity generation.

---

## 5. Search Workflow

The platform supports searching for photos using their associated metadata.

A user can search by providing:

* A photo name
* A language preference for captions or metadata interpretation

This adds discoverability to the media-sharing functionality and demonstrates the use of structured post entries.

---

## 6. Download Workflow

The image download process is one of the most educational parts of the project.

The server transfers images using a frame-based approach.

The process includes:

* Requesting a specific photo
* Locating the image on the server
* Initializing a handshake sequence
* Splitting the image into frames
* Sending frames in sequence
* Using acknowledgment logic for reliability
* Reconstructing the image on the client side
* Returning the associated caption

This workflow showcases custom file transfer behavior inspired by transport-layer concepts.

---

## 7. Repost and Comment Workflow

The platform also supports social interactions on shared content.

### Repost

A user can repost content from another accessible profile. The repost action creates a new activity entry and can notify relevant users.

### Comment

A user can comment on an existing post. The system records the interaction and updates the activity flow accordingly.

These features make the project feel much closer to a real social media platform rather than a simple messaging or storage application.

---

## Data Storage Structure

The project uses a file-based persistence model.

### Server-side structure

```text
data_server/
├── followers.txt
├── following.txt
├── extra_permissions.txt
├── <user_id>/
│   ├── Profile_<user_id>.txt
│   ├── Others_<user_id>.txt
│   └── photos/
│       ├── image1.jpg
│       ├── image2.png
│       └── ...
```

### Client-side structure

```text
data_client/
├── <user_id>/
│   ├── Others_<user_id>.txt
│   └── photos/
```

### Meaning of stored files

* `Profile_<id>.txt`: stores the user’s profile activity and post history
* `Others_<id>.txt`: stores updates, activity messages, and notification-like content
* `photos/`: stores uploaded or synchronized photo files
* `followers.txt`, `following.txt`, `extra_permissions.txt`: store the social graph and access data

This organization makes it easy to inspect the state of the application directly through the filesystem.

---

## Project Structure

```text
project/
├── src/
│   └── main/
│       ├── java/
│       │   ├── Client.java
│       │   ├── ClientHandler.java
│       │   ├── Frame.java
│       │   ├── LockManager.java
│       │   ├── ProfileManager.java
│       │   ├── Server.java
│       │   └── SocialGraph.java
│       └── resources/
│           └── SocialGraph.txt
├── data_server/
├── data_client/
├── README.md
└── .gitignore
```

---

## Technologies Used

* **Java**
* **TCP Sockets**
* **ObjectInputStream / ObjectOutputStream**
* **Java Concurrency Utilities**
* **ConcurrentHashMap**
* **Thread pools**
* **File I/O (NIO / Files API)**
* **Serializable objects for file transfer**

---

## Networking Concepts Demonstrated

This project is especially valuable because it demonstrates several important networking concepts in practice.

These include:

* Client-server communication
* Persistent socket connections
* Concurrent request handling
* Session management
* Application-level protocol design
* Command-based communication between client and server
* Reliable file transfer using frame sequencing
* Acknowledgment-based transmission flow
* Resource locking and synchronization

For students studying computer networks, this project provides a strong bridge between theory and implementation.

---

## Concurrency and Synchronization

The server is designed to support multiple users simultaneously.

Concurrency is handled through:

* A thread pool on the server
* Separate client handlers per connection
* Thread-safe collections in the social graph
* Lock-based access to profile files

This makes the system suitable for demonstrating how shared resources can be managed safely in a multi-user network application.

---

## Why This Project Is Interesting

This project stands out because it combines multiple dimensions of systems programming into one cohesive application.

It is not only a simple socket project and not only a social graph simulation. Instead, it integrates:

* networking
* concurrency
* file persistence
* social interaction logic
* multimedia sharing
* protocol-inspired file transfer
* access control and permissions

As a result, it serves as a complete educational example of applied Java networking and client-server systems.

---

## How to Run the Project

## Prerequisites

Make sure you have:

* **Java JDK 8+** installed
* A Java-compatible IDE or terminal
* Access to multiple terminal windows if you want to run several clients simultaneously

---

## Steps to Run

### 1. Compile the project

Compile all Java source files.

Example:

```bash
javac src/main/java/*.java
```

### 2. Start the server

Run the server first so it begins listening for client connections.

Example:

```bash
java -cp src/main/java Server
```

### 3. Start one or more clients

Open one or more new terminals and run the client application.

Example:

```bash
java -cp src/main/java Client
```

### 4. Use the menu options

From the client interface you can:

* register a user
* log in
* send follow requests
* upload photos
* search and download photos
* comment or repost content
* manage permissions
* view followers and following

---

## Example User Journey

A typical user session may look like this:

1. A user signs up and logs in
2. The user sends a follow request to another user
3. The request is accepted
4. The user uploads a photo with captions
5. Followers receive the update in their activity file
6. Another user accesses the profile and views the post
7. The post is downloaded through the frame-based transfer mechanism
8. The second user reposts or comments on the content
9. Additional activity entries and notifications are generated

This end-to-end flow shows how the different subsystems interact in a realistic way.

---

## Educational Value

This project is highly suitable for academic use in courses such as:

* Computer Networks
* Distributed Systems
* Concurrent Programming
* Operating Systems
* Java Programming
* Client-Server Application Development

It gives students a hands-on example of how to design and implement:

* a communication protocol
* a multithreaded backend
* a shared social state model
* a structured persistence layer
* file transfer logic over sockets

---

## Extensibility

The project has a modular structure and can be extended in many ways.

Potential future additions include:

* graphical user interface
* database-backed storage
* password authentication
* web-based frontend
* REST API version
* richer search capabilities
* better media metadata management
* improved notification system
* cloud deployment support

Its current architecture provides a strong foundation for further development.

---

## Repository Goals

This repository showcases:

* a complete Java socket-based social networking platform
* a practical client-server application
* support for social graph operations
* controlled profile access and permissions
* multimedia upload and download
* protocol-inspired frame transmission
* file-based persistence and synchronization

---


Example:

* Konstantinos Gougas
* Aris Karagiannakos
* Leonidas Panagis

---



---

## Final Notes

This project demonstrates how networking theory and software engineering can be combined to build a rich, interactive social platform in Java. By integrating sockets, concurrency, shared-state management, file persistence, and media transfer into one system, it provides a complete and impressive example of academic systems development.

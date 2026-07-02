# MCP Server PostgreSQL

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-blue.svg)](https://spring.io/projects/spring-ai)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A Model Context Protocol (MCP) server for PostgreSQL built with Spring Boot and Spring AI. This server provides read-only access to PostgreSQL databases through dynamic connections.

## ✅ Project Status

**COMPLETED**: The MCP server is fully functional with the following features:

- ✅ Spring Boot 3.4.1 + Spring AI 1.0.0 + Java 21
- ✅ Dynamic PostgreSQL connections (no hardcoded credentials)
- ✅ Read-only query validation and security
- ✅ Complete test suite with Testcontainers
- ✅ Docker Compose setup with sample data
- ✅ Production-ready Dockerfile
- ✅ Comprehensive documentation

## Features

- **Dynamic Database Connections**: Connect to any PostgreSQL database with runtime parameters
- **Read-Only Operations**: Ensures database safety by only allowing SELECT queries
- **MCP Tools**:
  - `list_schemas`: List all schemas in a database
  - `list_tables`: List all tables in a specific schema  
  - `select`: Select rows from a table with optional conditions and ordering
  - `execute_query`: Execute read-only SQL queries
- **Built with Modern Stack**: Spring Boot 3.4.1, Spring AI 1.0.0, Java 21

## Quick Start

### Prerequisites

- Java 21
- Maven 3.9+
- Docker & Docker Compose (for testing)

### Running the Server

1. **Clone and build the project:**
   ```bash
   git clone https://github.com/luanvuhlu/mcp-server-postgres.git
   cd mcp-server-postgres
   mvn clean compile
   ```

2. **Start test PostgreSQL database:**
   ```bash
   docker-compose up -d
   ```

3. **Run the MCP server:**
   ```bash
   mvn spring-boot:run
   ```

The MCP server will start and be ready to accept connections via the Model Context Protocol.

### Testing with Sample Data

The Docker Compose setup includes a PostgreSQL instance with sample data:

- **Host**: localhost
- **Port**: 5432
- **Database**: postgres
- **Username**: postgres
- **Password**: your_name

Sample schemas and tables are automatically created:
- `public` schema: users, products
- `sales` schema: orders, order_items
- `inventory` schema: stock

## Using with MCP Clients

This server can be used with any MCP-compatible client. Here are some examples:

### Claude Desktop

Add the following to your Claude Desktop configuration file:

```json
{
  "mcpServers": {
    "postgres": {
      "command": "java",
      "args": ["-jar", "target/mcp-server-postgres-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

### Other MCP Clients

The server implements the standard MCP protocol and can be used with any compatible client by running:

```bash
java -jar target/mcp-server-postgres-1.0.0-SNAPSHOT.jar
```

## MCP Tools Usage

### List Schemas

List all schemas in a PostgreSQL database:

```json
{
  "name": "list_schemas",
  "arguments": {
    "host": "localhost",
    "database": "postgres", 
    "username": "postgres",
    "password": "your_name"
  }
}
```

### List Tables

List all tables in a specific schema:

```json
{
  "name": "list_tables",
  "arguments": {
    "host": "localhost",
    "database": "postgres",
    "username": "postgres", 
    "password": "your_name",
    "schema": "public"
  }
}
```

### Select

Select rows from a table with optional conditions and ordering:

```json
{
  "name": "select",
  "arguments": {
    "host": "localhost",
    "database": "postgres",
    "username": "postgres", 
    "password": "your_name",
    "table": "users",
    "schema": "public",
    "conditions": "id > 1",
    "orderBy": "username ASC",
    "limit": 10
  }
}
```

### Execute Query

Execute a read-only SQL query:

```json
{
  "name": "execute_query",
  "arguments": {
    "host": "localhost",
    "database": "postgres",
    "username": "postgres",
    "password": "your_name", 
    "query": "SELECT * FROM users LIMIT 10"
  }
}
```

## Security

- **Read-Only**: Only SELECT, WITH, SHOW, EXPLAIN, DESCRIBE queries are allowed
- **Dynamic Connections**: No hardcoded database credentials
- **Input Validation**: Queries are validated before execution
- **Error Handling**: Proper error messages without exposing sensitive information

## Configuration

### Application Properties

Configure the MCP server in `src/main/resources/application.yml`:

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        port: 3000
```

### Database Connections

All database connections are dynamic and provided per request. No default database connection is configured.

### MCP Server Protocol

This server implements the Model Context Protocol (MCP) specification and communicates via JSON-RPC over stdio or TCP. The server registers the following tools that can be called by MCP-compatible clients.

## Development

### Project Structure

```
src/main/java/com/luanvv/mcp/
├── McpServerPostgresApplication.java    # Main application
├── config/
│   └── McpServerConfig.java            # MCP configuration
├── model/
│   └── DatabaseConnection.java         # Connection model
├── service/
│   └── PostgresService.java           # Database operations
└── tools/
    ├── ListSchemaTool.java            # List schemas tool
    ├── ListTablesTool.java           # List tables tool
    ├── SelectTool.java               # Select queries tool
    └── ExecuteQueryTool.java         # Execute query tool
```

### Building

```bash
# Compile
mvn clean compile

# Run tests
mvn test

# Package
mvn clean package

# Run
mvn spring-boot:run
```

### Docker

Build and run with Docker:

```bash
# Build image
docker build -t mcp-server-postgres .

# Run container
docker run -p 3000:3000 mcp-server-postgres
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the MIT License.

## Support

For issues and questions, please use the GitHub issue tracker.

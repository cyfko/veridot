---
layout: page
title: Node.js/TypeScript Guide
permalink: /docs/nodejs-guide/
nav_order: 3
---

# Node.js/TypeScript Implementation Guide

This guide covers the complete Node.js/TypeScript implementation of Veridot using the `dverify` package.

## Table of Contents
- [Installation](#installation)
- [Environment Configuration](#environment-configuration)
- [Basic Usage](#basic-usage)
- [Advanced Usage](#advanced-usage)
- [TypeScript Integration](#typescript-integration)
- [Production Deployment](#production-deployment)
- [Performance Optimization](#performance-optimization)

## Installation

### npm
```bash
npm install dverify
```

### Yarn
```bash
yarn add dverify
```

### pnpm
```bash
pnpm add dverify
```

## Environment Configuration

Create a `.env` file in your project root:

```bash
# Required: Kafka configuration
KAFKA_BROKER=localhost:9092
DVERIFY_KAFKA_TOPIC=veridot_public_keys

# Optional: Advanced configuration
DVERIFY_DB_PATH=./data/veridot              # LMDB storage path
DVERIFY_KEY_ROTATION_MS=3600000             # 1 hour key rotation
DVERIFY_CLEANUP_INTERVAL_MS=1800000         # 30 min cleanup interval
```

### Configuration Options

| Variable | Description | Default |
|----------|-------------|---------|
| `KAFKA_BROKER` | Kafka broker URL | `localhost:9093` |
| `DVERIFY_KAFKA_TOPIC` | Topic for key exchange | `public_keys_topic` |
| `DVERIFY_DB_PATH` | LMDB storage path | `./signer-db` |
| `DVERIFY_KEY_ROTATION_MS` | Key rotation interval | `3600000` (1h) |
| `DVERIFY_CLEANUP_INTERVAL_MS` | Expired key cleanup | `1800000` (30min) |

## Basic Usage

### Simple Mode (Recommended)

The `DVerify` class provides a unified interface for both signing and verification:

```typescript
import { DVerify } from 'dverify';

// Initialize (reads from environment variables)
const dverify = new DVerify();

// Sign data
const userData = {
  userId: '12345',
  email: 'user@example.com',
  roles: ['user', 'premium']
};

const { token } = await dverify.sign(userData, 3600); // 1 hour validity
console.log('Token:', token);

// Verify token
const result = await dverify.verify<typeof userData>(token);
if (result.valid) {
  console.log('User ID:', result.data.userId);
  console.log('Email:', result.data.email);
  console.log('Roles:', result.data.roles);
} else {
  console.log('Invalid token');
}
```

### Advanced Mode (Microservices)

For microservice architectures where signing and verification happen in different services:

```typescript
import { DataSigner, DataVerifier } from 'dverify';

// Service A: Token Signing
const signer = new DataSigner();

const orderData = {
  orderId: 'ORD-12345',
  customerId: 'CUST-789',
  items: [
    { sku: 'PROD-001', quantity: 2, price: 29.99 },
    { sku: 'PROD-002', quantity: 1, price: 39.99 }
  ],
  total: 99.97,
  createdAt: new Date().toISOString()
};

const token = await signer.sign(orderData, 1800); // 30 minutes
```

```typescript
// Service B: Token Verification
const verifier = new DataVerifier();

try {
  const order = await verifier.verify<OrderData>(token);
  console.log(`Processing order ${order.orderId} for customer ${order.customerId}`);
  
  // Process the order...
  for (const item of order.items) {
    console.log(`- ${item.sku}: ${item.quantity} x $${item.price}`);
  }
  
} catch (error) {
  console.error('Invalid order token:', error.message);
  // Handle invalid token...
}
```

## Advanced Usage

### Custom Configuration

```typescript
import { DataSigner, DataVerifier } from 'dverify';

// Custom configuration for specific environments
const signer = new DataSigner(
  'kafka-cluster:9092',           // broker
  'custom_topic',                 // topic
  './custom/db/path',             // dbPath
  1800000                         // rotation interval (30 min)
);

const verifier = new DataVerifier(
  'kafka-cluster:9092',           // broker
  'custom_topic',                 // topic  
  './custom/db/path',             // dbPath
  900000                          // cleanup interval (15 min)
);
```

### Error Handling

```typescript
import { DVerify } from 'dverify';
import { JsonEncodingException } from 'dverify/exceptions';

const dverify = new DVerify();

// Comprehensive error handling for signing
async function signUserSession(user: User): Promise<string | null> {
  try {
    const sessionData = {
      userId: user.id,
      username: user.username,
      permissions: user.permissions,
      sessionId: crypto.randomUUID(),
      createdAt: Date.now()
    };
    
    const { token } = await dverify.sign(sessionData, 7200); // 2 hours
    return token;
    
  } catch (error) {
    if (error instanceof JsonEncodingException) {
      console.error('Failed to encode user data:', error.message);
    } else {
      console.error('Token signing failed:', error.message);
    }
    return null;
  }
}

// Comprehensive error handling for verification
async function verifyUserSession(token: string): Promise<UserSession | null> {
  try {
    const result = await dverify.verify<UserSession>(token);
    
    if (result.valid) {
      // Additional validation
      if (result.data.createdAt + (7200 * 1000) < Date.now()) {
        console.warn('Session expired');
        return null;
      }
      
      return result.data;
    }
    
    return null;
    
  } catch (error) {
    console.error('Token verification failed:', error.message);
    return null;
  }
}
```

### Batch Operations

```typescript
import { DataSigner } from 'dverify';

const signer = new DataSigner();

// Sign multiple items efficiently
async function signBatch<T>(items: T[], duration: number): Promise<string[]> {
  const tokens = await Promise.all(
    items.map(item => signer.sign(item, duration))
  );
  
  console.log(`Signed ${tokens.length} tokens`);
  return tokens;
}

// Usage
const orders = [
  { orderId: 'ORD-001', total: 99.99 },
  { orderId: 'ORD-002', total: 149.99 },
  { orderId: 'ORD-003', total: 79.99 }
];

const tokens = await signBatch(orders, 3600);
```

## TypeScript Integration

### Type Definitions

```typescript
// types/user.ts
export interface User {
  id: string;
  email: string;
  username: string;
  roles: string[];
  createdAt: string;
}

export interface UserSession {
  userId: string;
  sessionId: string;
  permissions: string[];
  expiresAt: number;
}

export interface OrderData {
  orderId: string;
  customerId: string;
  items: OrderItem[];
  total: number;
  createdAt: string;
}

export interface OrderItem {
  sku: string;
  quantity: number;
  price: number;
}
```

### Service Class Implementation

```typescript
// services/TokenService.ts
import { DVerify } from 'dverify';
import { User, UserSession } from '../types/user';

export class TokenService {
  private readonly dverify: DVerify;
  
  constructor() {
    this.dverify = new DVerify();
  }
  
  async createUserSession(user: User, durationSeconds = 3600): Promise<string> {
    const sessionData: UserSession = {
      userId: user.id,
      sessionId: crypto.randomUUID(),
      permissions: this.calculatePermissions(user.roles),
      expiresAt: Date.now() + (durationSeconds * 1000)
    };
    
    const { token } = await this.dverify.sign(sessionData, durationSeconds);
    return token;
  }
  
  async verifyUserSession(token: string): Promise<UserSession | null> {
    const result = await this.dverify.verify<UserSession>(token);
    
    if (!result.valid) {
      return null;
    }
    
    // Additional expiration check
    if (result.data.expiresAt < Date.now()) {
      console.warn('Session expired for user:', result.data.userId);
      return null;
    }
    
    return result.data;
  }
  
  private calculatePermissions(roles: string[]): string[] {
    const permissions: string[] = [];
    
    if (roles.includes('admin')) {
      permissions.push('READ', 'WRITE', 'DELETE', 'ADMIN');
    } else if (roles.includes('editor')) {
      permissions.push('READ', 'WRITE');
    } else {
      permissions.push('READ');
    }
    
    return permissions;
  }
}
```

### Express.js Middleware

```typescript
// middleware/authMiddleware.ts
import { Request, Response, NextFunction } from 'express';
import { TokenService } from '../services/TokenService';
import { UserSession } from '../types/user';

declare global {
  namespace Express {
    interface Request {
      userSession?: UserSession;
    }
  }
}

const tokenService = new TokenService();

export async function authenticateToken(
  req: Request, 
  res: Response, 
  next: NextFunction
): Promise<void> {
  const authHeader = req.headers.authorization;
  const token = authHeader?.split(' ')[1]; // Bearer TOKEN
  
  if (!token) {
    res.status(401).json({ error: 'Access token required' });
    return;
  }
  
  try {
    const session = await tokenService.verifyUserSession(token);
    
    if (!session) {
      res.status(401).json({ error: 'Invalid or expired token' });
      return;
    }
    
    req.userSession = session;
    next();
    
  } catch (error) {
    console.error('Authentication error:', error);
    res.status(500).json({ error: 'Authentication failed' });
  }
}

// Permission-based authorization
export function requirePermission(permission: string) {
  return (req: Request, res: Response, next: NextFunction): void => {
    if (!req.userSession) {
      res.status(401).json({ error: 'Not authenticated' });
      return;
    }
    
    if (!req.userSession.permissions.includes(permission)) {
      res.status(403).json({ error: 'Insufficient permissions' });
      return;
    }
    
    next();
  };
}
```

### Usage in Express App

```typescript
// app.ts
import express from 'express';
import { authenticateToken, requirePermission } from './middleware/authMiddleware';
import { TokenService } from './services/TokenService';

const app = express();
const tokenService = new TokenService();

app.use(express.json());

// Login endpoint
app.post('/auth/login', async (req, res) => {
  try {
    // Validate user credentials (implement your logic)
    const user = await validateCredentials(req.body.email, req.body.password);
    
    if (!user) {
      return res.status(401).json({ error: 'Invalid credentials' });
    }
    
    const token = await tokenService.createUserSession(user, 3600);
    
    res.json({
      token,
      user: {
        id: user.id,
        email: user.email,
        roles: user.roles
      }
    });
    
  } catch (error) {
    console.error('Login error:', error);
    res.status(500).json({ error: 'Login failed' });
  }
});

// Protected routes
app.get('/api/profile', authenticateToken, (req, res) => {
  res.json({
    userId: req.userSession!.userId,
    sessionId: req.userSession!.sessionId,
    permissions: req.userSession!.permissions
  });
});

app.delete('/api/admin/users/:id', 
  authenticateToken, 
  requirePermission('ADMIN'), 
  (req, res) => {
    // Admin-only functionality
    res.json({ message: 'User deleted' });
  }
);

app.listen(3000, () => {
  console.log('Server running on port 3000');
});
```

## Production Deployment

### Docker Configuration

```dockerfile
# Dockerfile
FROM node:18-alpine

WORKDIR /app

# Copy package files
COPY package*.json ./
RUN npm ci --only=production

# Copy application
COPY . .

# Build TypeScript
RUN npm run build

# Create directory for LMDB
RUN mkdir -p /app/data/veridot

# Set environment
ENV NODE_ENV=production
ENV DVERIFY_DB_PATH=/app/data/veridot

EXPOSE 3000

CMD ["npm", "start"]
```

### Docker Compose

```yaml
# docker-compose.yml
version: '3.8'
services:
  app:
    build: .
    environment:
      KAFKA_BROKER: kafka:9092
      DVERIFY_KAFKA_TOPIC: veridot_keys
      DVERIFY_DB_PATH: /app/data/veridot
      NODE_ENV: production
    volumes:
      - veridot_data:/app/data
    depends_on:
      - kafka
    ports:
      - "3000:3000"

  kafka:
    image: confluentinc/cp-kafka:latest
    environment:
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    depends_on:
      - zookeeper

  zookeeper:
    image: confluentinc/cp-zookeeper:latest
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

volumes:
  veridot_data:
```

### Health Checks

```typescript
// health.ts
import { DVerify } from 'dverify';

export class HealthCheck {
  private dverify: DVerify;
  
  constructor() {
    this.dverify = new DVerify();
  }
  
  async checkHealth(): Promise<{ status: string; details: any }> {
    try {
      // Test token signing and verification
      const testData = { test: true, timestamp: Date.now() };
      const { token } = await this.dverify.sign(testData, 60);
      
      const result = await this.dverify.verify(token);
      
      if (result.valid) {
        return {
          status: 'healthy',
          details: {
            veridot: 'operational',
            lastCheck: new Date().toISOString()
          }
        };
      } else {
        throw new Error('Token verification failed');
      }
      
    } catch (error) {
      return {
        status: 'unhealthy',
        details: {
          veridot: 'failed',
          error: error.message,
          lastCheck: new Date().toISOString()
        }
      };
    }
  }
}
```

## Performance Optimization

### Connection Pooling

```typescript
// Implement singleton pattern for better resource management
class VeridotManager {
  private static instance: DVerify;
  
  static getInstance(): DVerify {
    if (!this.instance) {
      this.instance = new DVerify();
    }
    return this.instance;
  }
}

export const dverify = VeridotManager.getInstance();
```

### Caching Strategy

```typescript
import NodeCache from 'node-cache';

class CachedTokenService {
  private cache = new NodeCache({ stdTTL: 300 }); // 5 minutes
  private dverify = new DVerify();
  
  async verifyWithCache(token: string): Promise<any> {
    // Check cache first
    const cached = this.cache.get(token);
    if (cached) {
      return cached;
    }
    
    // Verify and cache result
    const result = await this.dverify.verify(token);
    if (result.valid) {
      this.cache.set(token, result.data);
    }
    
    return result.data;
  }
}
```

## Next Steps

- Explore [Java implementation]({{ '/docs/java-guide' | relative_url }})
- Review [Security best practices]({{ '/docs/security' | relative_url }})
- Check [API Reference]({{ '/docs/api-reference' | relative_url }})
- See [Deployment examples]({{ '/docs/deployment' | relative_url }})
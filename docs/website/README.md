# Ligero Framework Documentation

This is the documentation website for the Ligero Framework, built with [Docusaurus 3](https://docusaurus.io/), a modern static website generator optimized for documentation.

## 🚀 Getting Started

### Prerequisites

- Node.js 16+ (LTS recommended)
- Yarn 1.22+ or npm 8+
- Git

### Installation

1. Clone the repository (if you haven't already):
   ```bash
   git clone https://github.com/your-org/ligero.git
   cd ligero
   ```

2. Navigate to the website directory:
   ```bash
   cd docs/website
   ```

3. Install dependencies:
   ```bash
   # Using yarn (recommended)
   yarn
   
   # Or using npm
   npm install
   ```

## 🛠 Development

To start the local development server:

```bash
yarn start
# or
npm start
```

This will:
- Start a local development server at `http://localhost:3000`
- Open the website in your default browser
- Enable hot-reloading for most changes

## 🏗 Building for Production

To create a production build:

```bash
yarn build
# or
npm run build
```

This will generate static content in the `build` directory, which can be served by any static web server.

## 🚀 Deployment

### Using GitHub Pages

1. Set up your repository to use GitHub Pages (Settings > Pages)
2. Deploy using the following command:

```bash
# Using SSH
USE_SSH=true yarn deploy

# Or without SSH
GIT_USER=<YourGitHubUsername> yarn deploy
```

### Manual Deployment

Build the site and deploy the contents of the `build` directory to your preferred static hosting service (Netlify, Vercel, S3, etc.).

## 📝 Contributing to Documentation

1. Create a new branch for your changes
2. Make your changes to the markdown files in `docs/`
3. Test your changes locally
4. Submit a pull request with a clear description of your changes

## 📚 Documentation Structure

- `docs/` - Contains all documentation markdown files
- `src/` - Contains React components and custom styles
- `static/` - Static assets (images, downloads, etc.)
- `sidebars.js` - Defines the documentation sidebar structure
- `docusaurus.config.js` - Main configuration file

## 🧪 Testing

To run tests:

```bash
yarn test
# or
npm test
```

## 📄 License

This project is licensed under the Apache 2.0 License - see the [LICENSE](LICENSE) file for details.

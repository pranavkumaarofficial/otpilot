FROM node:22-alpine
WORKDIR /app
COPY package.json .
RUN npm install --production
COPY server/ server/
COPY public/ public/
EXPOSE 7890
CMD ["node", "server/index.js"]

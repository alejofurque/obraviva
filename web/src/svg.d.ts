// El build importa los .svg como texto (loader en angular.json). Esta declaración
// le dice a TypeScript qué tipo tiene ese import.
declare module '*.svg' {
  const content: string;
  export default content;
}

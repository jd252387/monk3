import { PH, type MappingDoc, type PhysicalField, type VirtualDoc } from './model';

// In-memory demo documents from the design spec — exercise every editor
// feature (vector, subdocuments, sourcing modes, morphologies, all virtual
// field kinds). Replaced by real file loading once a backend API exists.

export const demoMapping: MappingDoc = {
  root: {
    // 'identifier' holds a raw query node, not a field definition; the editor skips it.
    identifier: {
      field: 'materialType',
      data: { type: 'text', phrases: [{ type: 'phrase', value: 'product' }] },
    } as unknown as PhysicalField,
    sku: { type: 'string', destinationField: 'sku_s', sourcing: { pim: '.sku', erp: { jsonPointer: '/item/sku' } } },
    title: { type: 'freetext', morphologies: { english: 'title_en' }, sourcing: { pim: '.name', default: '.title' } },
    description: { type: 'freetext', sourcing: { pim: '.description.long' } },
    brand: {
      type: 'string',
      destinationField: 'brand_s',
      aggregatable: true,
      sourcing: {
        pim: '.brand.name',
        erp: { jsonPointer: '/vendor/brandName', required: false },
        default: { jq: '.brand', partialUpdate: 'add-distinct' },
      },
    },
    price: { type: 'number', sortable: true, sourcing: { erp: { jsonPointer: '/pricing/list' } } },
    inStock: { type: 'boolean', sourcing: { erp: '.inventory.available > 0' } },
    releaseDate: { type: 'datetime', sourcing: { pim: '.launchDate' } },
    embedding: {
      type: 'vector',
      destinationField: 'emb_%i',
      start: 0,
      fetchable: false,
      sourcing: { pim: '.ml.embedding[]', default: { jsonPointer: '/embeddings/main' } },
    },
    variants: { type: 'subdocument', subdocumentType: 'variant', destinationField: 'variants', primaryKey: { pim: '.variantId' } },
    reviews: { type: 'subdocument', subdocumentType: 'review', destinationField: 'reviews', primaryKey: { default: '.reviewId' } },
  },
  variant: {
    color: { type: 'string', aggregatable: true, sourcing: { pim: '.color' } },
    size: { type: 'string', sourcing: { pim: '.size' } },
    stock: { type: 'number', sourcing: { erp: '' } },
  },
  review: {
    rating: { type: 'number', aggregatable: true, sourcing: { default: '.rating' } },
    text: { type: 'freetext', sourcing: { default: '.text' } },
    verified: { type: 'boolean', sourcing: { default: '.verified' } },
  },
};

export const demoVirtual: VirtualDoc = {
  root: {
    anyText: {
      type: 'freetext',
      expansion: {
        field: '',
        minimumMatch: 1,
        data: [
          { bool: 'should', field: 'title', data: PH },
          { bool: 'should', field: 'description', data: PH },
        ],
      },
    },
    inStockNow: {
      type: 'predicate',
      expansion: {
        field: '',
        data: [{ bool: 'must', field: 'inStock', data: { type: 'exact', values: [true] } }],
      },
    },
    reviewMatch: {
      type: 'subquery',
      expansion: {
        field: 'review',
        data: [
          { bool: 'must', field: 'rating', data: { type: 'range', gte: 4 } },
          { bool: 'must', field: '', data: PH },
        ],
      },
    },
  },
  variant: {},
  review: {},
};

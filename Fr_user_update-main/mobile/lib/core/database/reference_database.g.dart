// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'reference_database.dart';

// ignore_for_file: type=lint
class $ReferenceListsTable extends ReferenceLists
    with TableInfo<$ReferenceListsTable, ReferenceList> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $ReferenceListsTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _listCodeMeta = const VerificationMeta(
    'listCode',
  );
  @override
  late final GeneratedColumn<String> listCode = GeneratedColumn<String>(
    'list_code',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _versionMeta = const VerificationMeta(
    'version',
  );
  @override
  late final GeneratedColumn<int> version = GeneratedColumn<int>(
    'version',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _contentHashMeta = const VerificationMeta(
    'contentHash',
  );
  @override
  late final GeneratedColumn<String> contentHash = GeneratedColumn<String>(
    'content_hash',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _itemCountMeta = const VerificationMeta(
    'itemCount',
  );
  @override
  late final GeneratedColumn<int> itemCount = GeneratedColumn<int>(
    'item_count',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _isHierarchicalMeta = const VerificationMeta(
    'isHierarchical',
  );
  @override
  late final GeneratedColumn<bool> isHierarchical = GeneratedColumn<bool>(
    'is_hierarchical',
    aliasedName,
    false,
    type: DriftSqlType.bool,
    requiredDuringInsert: true,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("is_hierarchical" IN (0, 1))',
    ),
  );
  static const VerificationMeta _rootItemCodeMeta = const VerificationMeta(
    'rootItemCode',
  );
  @override
  late final GeneratedColumn<String> rootItemCode = GeneratedColumn<String>(
    'root_item_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _rootCountryVersionMeta =
      const VerificationMeta('rootCountryVersion');
  @override
  late final GeneratedColumn<int> rootCountryVersion = GeneratedColumn<int>(
    'root_country_version',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _nameArMeta = const VerificationMeta('nameAr');
  @override
  late final GeneratedColumn<String> nameAr = GeneratedColumn<String>(
    'name_ar',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _nameEnMeta = const VerificationMeta('nameEn');
  @override
  late final GeneratedColumn<String> nameEn = GeneratedColumn<String>(
    'name_en',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _publishedAtMeta = const VerificationMeta(
    'publishedAt',
  );
  @override
  late final GeneratedColumn<DateTime> publishedAt = GeneratedColumn<DateTime>(
    'published_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _fetchedAtMeta = const VerificationMeta(
    'fetchedAt',
  );
  @override
  late final GeneratedColumn<DateTime> fetchedAt = GeneratedColumn<DateTime>(
    'fetched_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _isActiveVersionMeta = const VerificationMeta(
    'isActiveVersion',
  );
  @override
  late final GeneratedColumn<bool> isActiveVersion = GeneratedColumn<bool>(
    'is_active_version',
    aliasedName,
    false,
    type: DriftSqlType.bool,
    requiredDuringInsert: false,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("is_active_version" IN (0, 1))',
    ),
    defaultValue: const Constant(false),
  );
  @override
  List<GeneratedColumn> get $columns => [
    listCode,
    version,
    contentHash,
    itemCount,
    isHierarchical,
    rootItemCode,
    rootCountryVersion,
    nameAr,
    nameEn,
    publishedAt,
    fetchedAt,
    isActiveVersion,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'reference_lists';
  @override
  VerificationContext validateIntegrity(
    Insertable<ReferenceList> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('list_code')) {
      context.handle(
        _listCodeMeta,
        listCode.isAcceptableOrUnknown(data['list_code']!, _listCodeMeta),
      );
    } else if (isInserting) {
      context.missing(_listCodeMeta);
    }
    if (data.containsKey('version')) {
      context.handle(
        _versionMeta,
        version.isAcceptableOrUnknown(data['version']!, _versionMeta),
      );
    } else if (isInserting) {
      context.missing(_versionMeta);
    }
    if (data.containsKey('content_hash')) {
      context.handle(
        _contentHashMeta,
        contentHash.isAcceptableOrUnknown(
          data['content_hash']!,
          _contentHashMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_contentHashMeta);
    }
    if (data.containsKey('item_count')) {
      context.handle(
        _itemCountMeta,
        itemCount.isAcceptableOrUnknown(data['item_count']!, _itemCountMeta),
      );
    } else if (isInserting) {
      context.missing(_itemCountMeta);
    }
    if (data.containsKey('is_hierarchical')) {
      context.handle(
        _isHierarchicalMeta,
        isHierarchical.isAcceptableOrUnknown(
          data['is_hierarchical']!,
          _isHierarchicalMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_isHierarchicalMeta);
    }
    if (data.containsKey('root_item_code')) {
      context.handle(
        _rootItemCodeMeta,
        rootItemCode.isAcceptableOrUnknown(
          data['root_item_code']!,
          _rootItemCodeMeta,
        ),
      );
    }
    if (data.containsKey('root_country_version')) {
      context.handle(
        _rootCountryVersionMeta,
        rootCountryVersion.isAcceptableOrUnknown(
          data['root_country_version']!,
          _rootCountryVersionMeta,
        ),
      );
    }
    if (data.containsKey('name_ar')) {
      context.handle(
        _nameArMeta,
        nameAr.isAcceptableOrUnknown(data['name_ar']!, _nameArMeta),
      );
    } else if (isInserting) {
      context.missing(_nameArMeta);
    }
    if (data.containsKey('name_en')) {
      context.handle(
        _nameEnMeta,
        nameEn.isAcceptableOrUnknown(data['name_en']!, _nameEnMeta),
      );
    } else if (isInserting) {
      context.missing(_nameEnMeta);
    }
    if (data.containsKey('published_at')) {
      context.handle(
        _publishedAtMeta,
        publishedAt.isAcceptableOrUnknown(
          data['published_at']!,
          _publishedAtMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_publishedAtMeta);
    }
    if (data.containsKey('fetched_at')) {
      context.handle(
        _fetchedAtMeta,
        fetchedAt.isAcceptableOrUnknown(data['fetched_at']!, _fetchedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_fetchedAtMeta);
    }
    if (data.containsKey('is_active_version')) {
      context.handle(
        _isActiveVersionMeta,
        isActiveVersion.isAcceptableOrUnknown(
          data['is_active_version']!,
          _isActiveVersionMeta,
        ),
      );
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {listCode, version};
  @override
  ReferenceList map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return ReferenceList(
      listCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}list_code'],
      )!,
      version: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}version'],
      )!,
      contentHash: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}content_hash'],
      )!,
      itemCount: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}item_count'],
      )!,
      isHierarchical: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}is_hierarchical'],
      )!,
      rootItemCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}root_item_code'],
      ),
      rootCountryVersion: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}root_country_version'],
      ),
      nameAr: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}name_ar'],
      )!,
      nameEn: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}name_en'],
      )!,
      publishedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}published_at'],
      )!,
      fetchedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}fetched_at'],
      )!,
      isActiveVersion: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}is_active_version'],
      )!,
    );
  }

  @override
  $ReferenceListsTable createAlias(String alias) {
    return $ReferenceListsTable(attachedDatabase, alias);
  }
}

class ReferenceList extends DataClass implements Insertable<ReferenceList> {
  final String listCode;
  final int version;
  final String contentHash;
  final int itemCount;
  final bool isHierarchical;
  final String? rootItemCode;
  final int? rootCountryVersion;
  final String nameAr;
  final String nameEn;
  final DateTime publishedAt;
  final DateTime fetchedAt;
  final bool isActiveVersion;
  const ReferenceList({
    required this.listCode,
    required this.version,
    required this.contentHash,
    required this.itemCount,
    required this.isHierarchical,
    this.rootItemCode,
    this.rootCountryVersion,
    required this.nameAr,
    required this.nameEn,
    required this.publishedAt,
    required this.fetchedAt,
    required this.isActiveVersion,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['list_code'] = Variable<String>(listCode);
    map['version'] = Variable<int>(version);
    map['content_hash'] = Variable<String>(contentHash);
    map['item_count'] = Variable<int>(itemCount);
    map['is_hierarchical'] = Variable<bool>(isHierarchical);
    if (!nullToAbsent || rootItemCode != null) {
      map['root_item_code'] = Variable<String>(rootItemCode);
    }
    if (!nullToAbsent || rootCountryVersion != null) {
      map['root_country_version'] = Variable<int>(rootCountryVersion);
    }
    map['name_ar'] = Variable<String>(nameAr);
    map['name_en'] = Variable<String>(nameEn);
    map['published_at'] = Variable<DateTime>(publishedAt);
    map['fetched_at'] = Variable<DateTime>(fetchedAt);
    map['is_active_version'] = Variable<bool>(isActiveVersion);
    return map;
  }

  ReferenceListsCompanion toCompanion(bool nullToAbsent) {
    return ReferenceListsCompanion(
      listCode: Value(listCode),
      version: Value(version),
      contentHash: Value(contentHash),
      itemCount: Value(itemCount),
      isHierarchical: Value(isHierarchical),
      rootItemCode: rootItemCode == null && nullToAbsent
          ? const Value.absent()
          : Value(rootItemCode),
      rootCountryVersion: rootCountryVersion == null && nullToAbsent
          ? const Value.absent()
          : Value(rootCountryVersion),
      nameAr: Value(nameAr),
      nameEn: Value(nameEn),
      publishedAt: Value(publishedAt),
      fetchedAt: Value(fetchedAt),
      isActiveVersion: Value(isActiveVersion),
    );
  }

  factory ReferenceList.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return ReferenceList(
      listCode: serializer.fromJson<String>(json['listCode']),
      version: serializer.fromJson<int>(json['version']),
      contentHash: serializer.fromJson<String>(json['contentHash']),
      itemCount: serializer.fromJson<int>(json['itemCount']),
      isHierarchical: serializer.fromJson<bool>(json['isHierarchical']),
      rootItemCode: serializer.fromJson<String?>(json['rootItemCode']),
      rootCountryVersion: serializer.fromJson<int?>(json['rootCountryVersion']),
      nameAr: serializer.fromJson<String>(json['nameAr']),
      nameEn: serializer.fromJson<String>(json['nameEn']),
      publishedAt: serializer.fromJson<DateTime>(json['publishedAt']),
      fetchedAt: serializer.fromJson<DateTime>(json['fetchedAt']),
      isActiveVersion: serializer.fromJson<bool>(json['isActiveVersion']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'listCode': serializer.toJson<String>(listCode),
      'version': serializer.toJson<int>(version),
      'contentHash': serializer.toJson<String>(contentHash),
      'itemCount': serializer.toJson<int>(itemCount),
      'isHierarchical': serializer.toJson<bool>(isHierarchical),
      'rootItemCode': serializer.toJson<String?>(rootItemCode),
      'rootCountryVersion': serializer.toJson<int?>(rootCountryVersion),
      'nameAr': serializer.toJson<String>(nameAr),
      'nameEn': serializer.toJson<String>(nameEn),
      'publishedAt': serializer.toJson<DateTime>(publishedAt),
      'fetchedAt': serializer.toJson<DateTime>(fetchedAt),
      'isActiveVersion': serializer.toJson<bool>(isActiveVersion),
    };
  }

  ReferenceList copyWith({
    String? listCode,
    int? version,
    String? contentHash,
    int? itemCount,
    bool? isHierarchical,
    Value<String?> rootItemCode = const Value.absent(),
    Value<int?> rootCountryVersion = const Value.absent(),
    String? nameAr,
    String? nameEn,
    DateTime? publishedAt,
    DateTime? fetchedAt,
    bool? isActiveVersion,
  }) => ReferenceList(
    listCode: listCode ?? this.listCode,
    version: version ?? this.version,
    contentHash: contentHash ?? this.contentHash,
    itemCount: itemCount ?? this.itemCount,
    isHierarchical: isHierarchical ?? this.isHierarchical,
    rootItemCode: rootItemCode.present ? rootItemCode.value : this.rootItemCode,
    rootCountryVersion: rootCountryVersion.present
        ? rootCountryVersion.value
        : this.rootCountryVersion,
    nameAr: nameAr ?? this.nameAr,
    nameEn: nameEn ?? this.nameEn,
    publishedAt: publishedAt ?? this.publishedAt,
    fetchedAt: fetchedAt ?? this.fetchedAt,
    isActiveVersion: isActiveVersion ?? this.isActiveVersion,
  );
  ReferenceList copyWithCompanion(ReferenceListsCompanion data) {
    return ReferenceList(
      listCode: data.listCode.present ? data.listCode.value : this.listCode,
      version: data.version.present ? data.version.value : this.version,
      contentHash: data.contentHash.present
          ? data.contentHash.value
          : this.contentHash,
      itemCount: data.itemCount.present ? data.itemCount.value : this.itemCount,
      isHierarchical: data.isHierarchical.present
          ? data.isHierarchical.value
          : this.isHierarchical,
      rootItemCode: data.rootItemCode.present
          ? data.rootItemCode.value
          : this.rootItemCode,
      rootCountryVersion: data.rootCountryVersion.present
          ? data.rootCountryVersion.value
          : this.rootCountryVersion,
      nameAr: data.nameAr.present ? data.nameAr.value : this.nameAr,
      nameEn: data.nameEn.present ? data.nameEn.value : this.nameEn,
      publishedAt: data.publishedAt.present
          ? data.publishedAt.value
          : this.publishedAt,
      fetchedAt: data.fetchedAt.present ? data.fetchedAt.value : this.fetchedAt,
      isActiveVersion: data.isActiveVersion.present
          ? data.isActiveVersion.value
          : this.isActiveVersion,
    );
  }

  @override
  String toString() {
    return (StringBuffer('ReferenceList(')
          ..write('listCode: $listCode, ')
          ..write('version: $version, ')
          ..write('contentHash: $contentHash, ')
          ..write('itemCount: $itemCount, ')
          ..write('isHierarchical: $isHierarchical, ')
          ..write('rootItemCode: $rootItemCode, ')
          ..write('rootCountryVersion: $rootCountryVersion, ')
          ..write('nameAr: $nameAr, ')
          ..write('nameEn: $nameEn, ')
          ..write('publishedAt: $publishedAt, ')
          ..write('fetchedAt: $fetchedAt, ')
          ..write('isActiveVersion: $isActiveVersion')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    listCode,
    version,
    contentHash,
    itemCount,
    isHierarchical,
    rootItemCode,
    rootCountryVersion,
    nameAr,
    nameEn,
    publishedAt,
    fetchedAt,
    isActiveVersion,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is ReferenceList &&
          other.listCode == this.listCode &&
          other.version == this.version &&
          other.contentHash == this.contentHash &&
          other.itemCount == this.itemCount &&
          other.isHierarchical == this.isHierarchical &&
          other.rootItemCode == this.rootItemCode &&
          other.rootCountryVersion == this.rootCountryVersion &&
          other.nameAr == this.nameAr &&
          other.nameEn == this.nameEn &&
          other.publishedAt == this.publishedAt &&
          other.fetchedAt == this.fetchedAt &&
          other.isActiveVersion == this.isActiveVersion);
}

class ReferenceListsCompanion extends UpdateCompanion<ReferenceList> {
  final Value<String> listCode;
  final Value<int> version;
  final Value<String> contentHash;
  final Value<int> itemCount;
  final Value<bool> isHierarchical;
  final Value<String?> rootItemCode;
  final Value<int?> rootCountryVersion;
  final Value<String> nameAr;
  final Value<String> nameEn;
  final Value<DateTime> publishedAt;
  final Value<DateTime> fetchedAt;
  final Value<bool> isActiveVersion;
  final Value<int> rowid;
  const ReferenceListsCompanion({
    this.listCode = const Value.absent(),
    this.version = const Value.absent(),
    this.contentHash = const Value.absent(),
    this.itemCount = const Value.absent(),
    this.isHierarchical = const Value.absent(),
    this.rootItemCode = const Value.absent(),
    this.rootCountryVersion = const Value.absent(),
    this.nameAr = const Value.absent(),
    this.nameEn = const Value.absent(),
    this.publishedAt = const Value.absent(),
    this.fetchedAt = const Value.absent(),
    this.isActiveVersion = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  ReferenceListsCompanion.insert({
    required String listCode,
    required int version,
    required String contentHash,
    required int itemCount,
    required bool isHierarchical,
    this.rootItemCode = const Value.absent(),
    this.rootCountryVersion = const Value.absent(),
    required String nameAr,
    required String nameEn,
    required DateTime publishedAt,
    required DateTime fetchedAt,
    this.isActiveVersion = const Value.absent(),
    this.rowid = const Value.absent(),
  }) : listCode = Value(listCode),
       version = Value(version),
       contentHash = Value(contentHash),
       itemCount = Value(itemCount),
       isHierarchical = Value(isHierarchical),
       nameAr = Value(nameAr),
       nameEn = Value(nameEn),
       publishedAt = Value(publishedAt),
       fetchedAt = Value(fetchedAt);
  static Insertable<ReferenceList> custom({
    Expression<String>? listCode,
    Expression<int>? version,
    Expression<String>? contentHash,
    Expression<int>? itemCount,
    Expression<bool>? isHierarchical,
    Expression<String>? rootItemCode,
    Expression<int>? rootCountryVersion,
    Expression<String>? nameAr,
    Expression<String>? nameEn,
    Expression<DateTime>? publishedAt,
    Expression<DateTime>? fetchedAt,
    Expression<bool>? isActiveVersion,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (listCode != null) 'list_code': listCode,
      if (version != null) 'version': version,
      if (contentHash != null) 'content_hash': contentHash,
      if (itemCount != null) 'item_count': itemCount,
      if (isHierarchical != null) 'is_hierarchical': isHierarchical,
      if (rootItemCode != null) 'root_item_code': rootItemCode,
      if (rootCountryVersion != null)
        'root_country_version': rootCountryVersion,
      if (nameAr != null) 'name_ar': nameAr,
      if (nameEn != null) 'name_en': nameEn,
      if (publishedAt != null) 'published_at': publishedAt,
      if (fetchedAt != null) 'fetched_at': fetchedAt,
      if (isActiveVersion != null) 'is_active_version': isActiveVersion,
      if (rowid != null) 'rowid': rowid,
    });
  }

  ReferenceListsCompanion copyWith({
    Value<String>? listCode,
    Value<int>? version,
    Value<String>? contentHash,
    Value<int>? itemCount,
    Value<bool>? isHierarchical,
    Value<String?>? rootItemCode,
    Value<int?>? rootCountryVersion,
    Value<String>? nameAr,
    Value<String>? nameEn,
    Value<DateTime>? publishedAt,
    Value<DateTime>? fetchedAt,
    Value<bool>? isActiveVersion,
    Value<int>? rowid,
  }) {
    return ReferenceListsCompanion(
      listCode: listCode ?? this.listCode,
      version: version ?? this.version,
      contentHash: contentHash ?? this.contentHash,
      itemCount: itemCount ?? this.itemCount,
      isHierarchical: isHierarchical ?? this.isHierarchical,
      rootItemCode: rootItemCode ?? this.rootItemCode,
      rootCountryVersion: rootCountryVersion ?? this.rootCountryVersion,
      nameAr: nameAr ?? this.nameAr,
      nameEn: nameEn ?? this.nameEn,
      publishedAt: publishedAt ?? this.publishedAt,
      fetchedAt: fetchedAt ?? this.fetchedAt,
      isActiveVersion: isActiveVersion ?? this.isActiveVersion,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (listCode.present) {
      map['list_code'] = Variable<String>(listCode.value);
    }
    if (version.present) {
      map['version'] = Variable<int>(version.value);
    }
    if (contentHash.present) {
      map['content_hash'] = Variable<String>(contentHash.value);
    }
    if (itemCount.present) {
      map['item_count'] = Variable<int>(itemCount.value);
    }
    if (isHierarchical.present) {
      map['is_hierarchical'] = Variable<bool>(isHierarchical.value);
    }
    if (rootItemCode.present) {
      map['root_item_code'] = Variable<String>(rootItemCode.value);
    }
    if (rootCountryVersion.present) {
      map['root_country_version'] = Variable<int>(rootCountryVersion.value);
    }
    if (nameAr.present) {
      map['name_ar'] = Variable<String>(nameAr.value);
    }
    if (nameEn.present) {
      map['name_en'] = Variable<String>(nameEn.value);
    }
    if (publishedAt.present) {
      map['published_at'] = Variable<DateTime>(publishedAt.value);
    }
    if (fetchedAt.present) {
      map['fetched_at'] = Variable<DateTime>(fetchedAt.value);
    }
    if (isActiveVersion.present) {
      map['is_active_version'] = Variable<bool>(isActiveVersion.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('ReferenceListsCompanion(')
          ..write('listCode: $listCode, ')
          ..write('version: $version, ')
          ..write('contentHash: $contentHash, ')
          ..write('itemCount: $itemCount, ')
          ..write('isHierarchical: $isHierarchical, ')
          ..write('rootItemCode: $rootItemCode, ')
          ..write('rootCountryVersion: $rootCountryVersion, ')
          ..write('nameAr: $nameAr, ')
          ..write('nameEn: $nameEn, ')
          ..write('publishedAt: $publishedAt, ')
          ..write('fetchedAt: $fetchedAt, ')
          ..write('isActiveVersion: $isActiveVersion, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $ReferenceItemsTable extends ReferenceItems
    with TableInfo<$ReferenceItemsTable, ReferenceItem> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $ReferenceItemsTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _listCodeMeta = const VerificationMeta(
    'listCode',
  );
  @override
  late final GeneratedColumn<String> listCode = GeneratedColumn<String>(
    'list_code',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _versionMeta = const VerificationMeta(
    'version',
  );
  @override
  late final GeneratedColumn<int> version = GeneratedColumn<int>(
    'version',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _itemCodeMeta = const VerificationMeta(
    'itemCode',
  );
  @override
  late final GeneratedColumn<String> itemCode = GeneratedColumn<String>(
    'item_code',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _parentCodeMeta = const VerificationMeta(
    'parentCode',
  );
  @override
  late final GeneratedColumn<String> parentCode = GeneratedColumn<String>(
    'parent_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _labelArMeta = const VerificationMeta(
    'labelAr',
  );
  @override
  late final GeneratedColumn<String> labelAr = GeneratedColumn<String>(
    'label_ar',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _labelEnMeta = const VerificationMeta(
    'labelEn',
  );
  @override
  late final GeneratedColumn<String> labelEn = GeneratedColumn<String>(
    'label_en',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _searchArMeta = const VerificationMeta(
    'searchAr',
  );
  @override
  late final GeneratedColumn<String> searchAr = GeneratedColumn<String>(
    'search_ar',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _searchEnMeta = const VerificationMeta(
    'searchEn',
  );
  @override
  late final GeneratedColumn<String> searchEn = GeneratedColumn<String>(
    'search_en',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _sortOrdinalMeta = const VerificationMeta(
    'sortOrdinal',
  );
  @override
  late final GeneratedColumn<int> sortOrdinal = GeneratedColumn<int>(
    'sort_ordinal',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _isActiveMeta = const VerificationMeta(
    'isActive',
  );
  @override
  late final GeneratedColumn<bool> isActive = GeneratedColumn<bool>(
    'is_active',
    aliasedName,
    false,
    type: DriftSqlType.bool,
    requiredDuringInsert: true,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("is_active" IN (0, 1))',
    ),
  );
  static const VerificationMeta _extraJsonMeta = const VerificationMeta(
    'extraJson',
  );
  @override
  late final GeneratedColumn<String> extraJson = GeneratedColumn<String>(
    'extra_json',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  @override
  List<GeneratedColumn> get $columns => [
    listCode,
    version,
    itemCode,
    parentCode,
    labelAr,
    labelEn,
    searchAr,
    searchEn,
    sortOrdinal,
    isActive,
    extraJson,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'reference_items';
  @override
  VerificationContext validateIntegrity(
    Insertable<ReferenceItem> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('list_code')) {
      context.handle(
        _listCodeMeta,
        listCode.isAcceptableOrUnknown(data['list_code']!, _listCodeMeta),
      );
    } else if (isInserting) {
      context.missing(_listCodeMeta);
    }
    if (data.containsKey('version')) {
      context.handle(
        _versionMeta,
        version.isAcceptableOrUnknown(data['version']!, _versionMeta),
      );
    } else if (isInserting) {
      context.missing(_versionMeta);
    }
    if (data.containsKey('item_code')) {
      context.handle(
        _itemCodeMeta,
        itemCode.isAcceptableOrUnknown(data['item_code']!, _itemCodeMeta),
      );
    } else if (isInserting) {
      context.missing(_itemCodeMeta);
    }
    if (data.containsKey('parent_code')) {
      context.handle(
        _parentCodeMeta,
        parentCode.isAcceptableOrUnknown(data['parent_code']!, _parentCodeMeta),
      );
    }
    if (data.containsKey('label_ar')) {
      context.handle(
        _labelArMeta,
        labelAr.isAcceptableOrUnknown(data['label_ar']!, _labelArMeta),
      );
    } else if (isInserting) {
      context.missing(_labelArMeta);
    }
    if (data.containsKey('label_en')) {
      context.handle(
        _labelEnMeta,
        labelEn.isAcceptableOrUnknown(data['label_en']!, _labelEnMeta),
      );
    }
    if (data.containsKey('search_ar')) {
      context.handle(
        _searchArMeta,
        searchAr.isAcceptableOrUnknown(data['search_ar']!, _searchArMeta),
      );
    } else if (isInserting) {
      context.missing(_searchArMeta);
    }
    if (data.containsKey('search_en')) {
      context.handle(
        _searchEnMeta,
        searchEn.isAcceptableOrUnknown(data['search_en']!, _searchEnMeta),
      );
    }
    if (data.containsKey('sort_ordinal')) {
      context.handle(
        _sortOrdinalMeta,
        sortOrdinal.isAcceptableOrUnknown(
          data['sort_ordinal']!,
          _sortOrdinalMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_sortOrdinalMeta);
    }
    if (data.containsKey('is_active')) {
      context.handle(
        _isActiveMeta,
        isActive.isAcceptableOrUnknown(data['is_active']!, _isActiveMeta),
      );
    } else if (isInserting) {
      context.missing(_isActiveMeta);
    }
    if (data.containsKey('extra_json')) {
      context.handle(
        _extraJsonMeta,
        extraJson.isAcceptableOrUnknown(data['extra_json']!, _extraJsonMeta),
      );
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {listCode, version, itemCode};
  @override
  ReferenceItem map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return ReferenceItem(
      listCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}list_code'],
      )!,
      version: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}version'],
      )!,
      itemCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}item_code'],
      )!,
      parentCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}parent_code'],
      ),
      labelAr: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}label_ar'],
      )!,
      labelEn: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}label_en'],
      ),
      searchAr: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}search_ar'],
      )!,
      searchEn: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}search_en'],
      ),
      sortOrdinal: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}sort_ordinal'],
      )!,
      isActive: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}is_active'],
      )!,
      extraJson: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}extra_json'],
      ),
    );
  }

  @override
  $ReferenceItemsTable createAlias(String alias) {
    return $ReferenceItemsTable(attachedDatabase, alias);
  }
}

class ReferenceItem extends DataClass implements Insertable<ReferenceItem> {
  final String listCode;
  final int version;
  final String itemCode;
  final String? parentCode;
  final String labelAr;
  final String? labelEn;
  final String searchAr;
  final String? searchEn;
  final int sortOrdinal;
  final bool isActive;
  final String? extraJson;
  const ReferenceItem({
    required this.listCode,
    required this.version,
    required this.itemCode,
    this.parentCode,
    required this.labelAr,
    this.labelEn,
    required this.searchAr,
    this.searchEn,
    required this.sortOrdinal,
    required this.isActive,
    this.extraJson,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['list_code'] = Variable<String>(listCode);
    map['version'] = Variable<int>(version);
    map['item_code'] = Variable<String>(itemCode);
    if (!nullToAbsent || parentCode != null) {
      map['parent_code'] = Variable<String>(parentCode);
    }
    map['label_ar'] = Variable<String>(labelAr);
    if (!nullToAbsent || labelEn != null) {
      map['label_en'] = Variable<String>(labelEn);
    }
    map['search_ar'] = Variable<String>(searchAr);
    if (!nullToAbsent || searchEn != null) {
      map['search_en'] = Variable<String>(searchEn);
    }
    map['sort_ordinal'] = Variable<int>(sortOrdinal);
    map['is_active'] = Variable<bool>(isActive);
    if (!nullToAbsent || extraJson != null) {
      map['extra_json'] = Variable<String>(extraJson);
    }
    return map;
  }

  ReferenceItemsCompanion toCompanion(bool nullToAbsent) {
    return ReferenceItemsCompanion(
      listCode: Value(listCode),
      version: Value(version),
      itemCode: Value(itemCode),
      parentCode: parentCode == null && nullToAbsent
          ? const Value.absent()
          : Value(parentCode),
      labelAr: Value(labelAr),
      labelEn: labelEn == null && nullToAbsent
          ? const Value.absent()
          : Value(labelEn),
      searchAr: Value(searchAr),
      searchEn: searchEn == null && nullToAbsent
          ? const Value.absent()
          : Value(searchEn),
      sortOrdinal: Value(sortOrdinal),
      isActive: Value(isActive),
      extraJson: extraJson == null && nullToAbsent
          ? const Value.absent()
          : Value(extraJson),
    );
  }

  factory ReferenceItem.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return ReferenceItem(
      listCode: serializer.fromJson<String>(json['listCode']),
      version: serializer.fromJson<int>(json['version']),
      itemCode: serializer.fromJson<String>(json['itemCode']),
      parentCode: serializer.fromJson<String?>(json['parentCode']),
      labelAr: serializer.fromJson<String>(json['labelAr']),
      labelEn: serializer.fromJson<String?>(json['labelEn']),
      searchAr: serializer.fromJson<String>(json['searchAr']),
      searchEn: serializer.fromJson<String?>(json['searchEn']),
      sortOrdinal: serializer.fromJson<int>(json['sortOrdinal']),
      isActive: serializer.fromJson<bool>(json['isActive']),
      extraJson: serializer.fromJson<String?>(json['extraJson']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'listCode': serializer.toJson<String>(listCode),
      'version': serializer.toJson<int>(version),
      'itemCode': serializer.toJson<String>(itemCode),
      'parentCode': serializer.toJson<String?>(parentCode),
      'labelAr': serializer.toJson<String>(labelAr),
      'labelEn': serializer.toJson<String?>(labelEn),
      'searchAr': serializer.toJson<String>(searchAr),
      'searchEn': serializer.toJson<String?>(searchEn),
      'sortOrdinal': serializer.toJson<int>(sortOrdinal),
      'isActive': serializer.toJson<bool>(isActive),
      'extraJson': serializer.toJson<String?>(extraJson),
    };
  }

  ReferenceItem copyWith({
    String? listCode,
    int? version,
    String? itemCode,
    Value<String?> parentCode = const Value.absent(),
    String? labelAr,
    Value<String?> labelEn = const Value.absent(),
    String? searchAr,
    Value<String?> searchEn = const Value.absent(),
    int? sortOrdinal,
    bool? isActive,
    Value<String?> extraJson = const Value.absent(),
  }) => ReferenceItem(
    listCode: listCode ?? this.listCode,
    version: version ?? this.version,
    itemCode: itemCode ?? this.itemCode,
    parentCode: parentCode.present ? parentCode.value : this.parentCode,
    labelAr: labelAr ?? this.labelAr,
    labelEn: labelEn.present ? labelEn.value : this.labelEn,
    searchAr: searchAr ?? this.searchAr,
    searchEn: searchEn.present ? searchEn.value : this.searchEn,
    sortOrdinal: sortOrdinal ?? this.sortOrdinal,
    isActive: isActive ?? this.isActive,
    extraJson: extraJson.present ? extraJson.value : this.extraJson,
  );
  ReferenceItem copyWithCompanion(ReferenceItemsCompanion data) {
    return ReferenceItem(
      listCode: data.listCode.present ? data.listCode.value : this.listCode,
      version: data.version.present ? data.version.value : this.version,
      itemCode: data.itemCode.present ? data.itemCode.value : this.itemCode,
      parentCode: data.parentCode.present
          ? data.parentCode.value
          : this.parentCode,
      labelAr: data.labelAr.present ? data.labelAr.value : this.labelAr,
      labelEn: data.labelEn.present ? data.labelEn.value : this.labelEn,
      searchAr: data.searchAr.present ? data.searchAr.value : this.searchAr,
      searchEn: data.searchEn.present ? data.searchEn.value : this.searchEn,
      sortOrdinal: data.sortOrdinal.present
          ? data.sortOrdinal.value
          : this.sortOrdinal,
      isActive: data.isActive.present ? data.isActive.value : this.isActive,
      extraJson: data.extraJson.present ? data.extraJson.value : this.extraJson,
    );
  }

  @override
  String toString() {
    return (StringBuffer('ReferenceItem(')
          ..write('listCode: $listCode, ')
          ..write('version: $version, ')
          ..write('itemCode: $itemCode, ')
          ..write('parentCode: $parentCode, ')
          ..write('labelAr: $labelAr, ')
          ..write('labelEn: $labelEn, ')
          ..write('searchAr: $searchAr, ')
          ..write('searchEn: $searchEn, ')
          ..write('sortOrdinal: $sortOrdinal, ')
          ..write('isActive: $isActive, ')
          ..write('extraJson: $extraJson')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    listCode,
    version,
    itemCode,
    parentCode,
    labelAr,
    labelEn,
    searchAr,
    searchEn,
    sortOrdinal,
    isActive,
    extraJson,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is ReferenceItem &&
          other.listCode == this.listCode &&
          other.version == this.version &&
          other.itemCode == this.itemCode &&
          other.parentCode == this.parentCode &&
          other.labelAr == this.labelAr &&
          other.labelEn == this.labelEn &&
          other.searchAr == this.searchAr &&
          other.searchEn == this.searchEn &&
          other.sortOrdinal == this.sortOrdinal &&
          other.isActive == this.isActive &&
          other.extraJson == this.extraJson);
}

class ReferenceItemsCompanion extends UpdateCompanion<ReferenceItem> {
  final Value<String> listCode;
  final Value<int> version;
  final Value<String> itemCode;
  final Value<String?> parentCode;
  final Value<String> labelAr;
  final Value<String?> labelEn;
  final Value<String> searchAr;
  final Value<String?> searchEn;
  final Value<int> sortOrdinal;
  final Value<bool> isActive;
  final Value<String?> extraJson;
  final Value<int> rowid;
  const ReferenceItemsCompanion({
    this.listCode = const Value.absent(),
    this.version = const Value.absent(),
    this.itemCode = const Value.absent(),
    this.parentCode = const Value.absent(),
    this.labelAr = const Value.absent(),
    this.labelEn = const Value.absent(),
    this.searchAr = const Value.absent(),
    this.searchEn = const Value.absent(),
    this.sortOrdinal = const Value.absent(),
    this.isActive = const Value.absent(),
    this.extraJson = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  ReferenceItemsCompanion.insert({
    required String listCode,
    required int version,
    required String itemCode,
    this.parentCode = const Value.absent(),
    required String labelAr,
    this.labelEn = const Value.absent(),
    required String searchAr,
    this.searchEn = const Value.absent(),
    required int sortOrdinal,
    required bool isActive,
    this.extraJson = const Value.absent(),
    this.rowid = const Value.absent(),
  }) : listCode = Value(listCode),
       version = Value(version),
       itemCode = Value(itemCode),
       labelAr = Value(labelAr),
       searchAr = Value(searchAr),
       sortOrdinal = Value(sortOrdinal),
       isActive = Value(isActive);
  static Insertable<ReferenceItem> custom({
    Expression<String>? listCode,
    Expression<int>? version,
    Expression<String>? itemCode,
    Expression<String>? parentCode,
    Expression<String>? labelAr,
    Expression<String>? labelEn,
    Expression<String>? searchAr,
    Expression<String>? searchEn,
    Expression<int>? sortOrdinal,
    Expression<bool>? isActive,
    Expression<String>? extraJson,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (listCode != null) 'list_code': listCode,
      if (version != null) 'version': version,
      if (itemCode != null) 'item_code': itemCode,
      if (parentCode != null) 'parent_code': parentCode,
      if (labelAr != null) 'label_ar': labelAr,
      if (labelEn != null) 'label_en': labelEn,
      if (searchAr != null) 'search_ar': searchAr,
      if (searchEn != null) 'search_en': searchEn,
      if (sortOrdinal != null) 'sort_ordinal': sortOrdinal,
      if (isActive != null) 'is_active': isActive,
      if (extraJson != null) 'extra_json': extraJson,
      if (rowid != null) 'rowid': rowid,
    });
  }

  ReferenceItemsCompanion copyWith({
    Value<String>? listCode,
    Value<int>? version,
    Value<String>? itemCode,
    Value<String?>? parentCode,
    Value<String>? labelAr,
    Value<String?>? labelEn,
    Value<String>? searchAr,
    Value<String?>? searchEn,
    Value<int>? sortOrdinal,
    Value<bool>? isActive,
    Value<String?>? extraJson,
    Value<int>? rowid,
  }) {
    return ReferenceItemsCompanion(
      listCode: listCode ?? this.listCode,
      version: version ?? this.version,
      itemCode: itemCode ?? this.itemCode,
      parentCode: parentCode ?? this.parentCode,
      labelAr: labelAr ?? this.labelAr,
      labelEn: labelEn ?? this.labelEn,
      searchAr: searchAr ?? this.searchAr,
      searchEn: searchEn ?? this.searchEn,
      sortOrdinal: sortOrdinal ?? this.sortOrdinal,
      isActive: isActive ?? this.isActive,
      extraJson: extraJson ?? this.extraJson,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (listCode.present) {
      map['list_code'] = Variable<String>(listCode.value);
    }
    if (version.present) {
      map['version'] = Variable<int>(version.value);
    }
    if (itemCode.present) {
      map['item_code'] = Variable<String>(itemCode.value);
    }
    if (parentCode.present) {
      map['parent_code'] = Variable<String>(parentCode.value);
    }
    if (labelAr.present) {
      map['label_ar'] = Variable<String>(labelAr.value);
    }
    if (labelEn.present) {
      map['label_en'] = Variable<String>(labelEn.value);
    }
    if (searchAr.present) {
      map['search_ar'] = Variable<String>(searchAr.value);
    }
    if (searchEn.present) {
      map['search_en'] = Variable<String>(searchEn.value);
    }
    if (sortOrdinal.present) {
      map['sort_ordinal'] = Variable<int>(sortOrdinal.value);
    }
    if (isActive.present) {
      map['is_active'] = Variable<bool>(isActive.value);
    }
    if (extraJson.present) {
      map['extra_json'] = Variable<String>(extraJson.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('ReferenceItemsCompanion(')
          ..write('listCode: $listCode, ')
          ..write('version: $version, ')
          ..write('itemCode: $itemCode, ')
          ..write('parentCode: $parentCode, ')
          ..write('labelAr: $labelAr, ')
          ..write('labelEn: $labelEn, ')
          ..write('searchAr: $searchAr, ')
          ..write('searchEn: $searchEn, ')
          ..write('sortOrdinal: $sortOrdinal, ')
          ..write('isActive: $isActive, ')
          ..write('extraJson: $extraJson, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $ReferenceListFailuresTable extends ReferenceListFailures
    with TableInfo<$ReferenceListFailuresTable, ReferenceListFailure> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $ReferenceListFailuresTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _listCodeMeta = const VerificationMeta(
    'listCode',
  );
  @override
  late final GeneratedColumn<String> listCode = GeneratedColumn<String>(
    'list_code',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _versionMeta = const VerificationMeta(
    'version',
  );
  @override
  late final GeneratedColumn<int> version = GeneratedColumn<int>(
    'version',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _totalFailureCountMeta = const VerificationMeta(
    'totalFailureCount',
  );
  @override
  late final GeneratedColumn<int> totalFailureCount = GeneratedColumn<int>(
    'total_failure_count',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    defaultValue: const Constant(0),
  );
  static const VerificationMeta _verificationFailureCountMeta =
      const VerificationMeta('verificationFailureCount');
  @override
  late final GeneratedColumn<int> verificationFailureCount =
      GeneratedColumn<int>(
        'verification_failure_count',
        aliasedName,
        false,
        type: DriftSqlType.int,
        requiredDuringInsert: false,
        defaultValue: const Constant(0),
      );
  static const VerificationMeta _lastFailureAtMeta = const VerificationMeta(
    'lastFailureAt',
  );
  @override
  late final GeneratedColumn<DateTime> lastFailureAt =
      GeneratedColumn<DateTime>(
        'last_failure_at',
        aliasedName,
        true,
        type: DriftSqlType.dateTime,
        requiredDuringInsert: false,
      );
  static const VerificationMeta _isPoisonedMeta = const VerificationMeta(
    'isPoisoned',
  );
  @override
  late final GeneratedColumn<bool> isPoisoned = GeneratedColumn<bool>(
    'is_poisoned',
    aliasedName,
    false,
    type: DriftSqlType.bool,
    requiredDuringInsert: false,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("is_poisoned" IN (0, 1))',
    ),
    defaultValue: const Constant(false),
  );
  @override
  List<GeneratedColumn> get $columns => [
    listCode,
    version,
    totalFailureCount,
    verificationFailureCount,
    lastFailureAt,
    isPoisoned,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'reference_list_failures';
  @override
  VerificationContext validateIntegrity(
    Insertable<ReferenceListFailure> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('list_code')) {
      context.handle(
        _listCodeMeta,
        listCode.isAcceptableOrUnknown(data['list_code']!, _listCodeMeta),
      );
    } else if (isInserting) {
      context.missing(_listCodeMeta);
    }
    if (data.containsKey('version')) {
      context.handle(
        _versionMeta,
        version.isAcceptableOrUnknown(data['version']!, _versionMeta),
      );
    } else if (isInserting) {
      context.missing(_versionMeta);
    }
    if (data.containsKey('total_failure_count')) {
      context.handle(
        _totalFailureCountMeta,
        totalFailureCount.isAcceptableOrUnknown(
          data['total_failure_count']!,
          _totalFailureCountMeta,
        ),
      );
    }
    if (data.containsKey('verification_failure_count')) {
      context.handle(
        _verificationFailureCountMeta,
        verificationFailureCount.isAcceptableOrUnknown(
          data['verification_failure_count']!,
          _verificationFailureCountMeta,
        ),
      );
    }
    if (data.containsKey('last_failure_at')) {
      context.handle(
        _lastFailureAtMeta,
        lastFailureAt.isAcceptableOrUnknown(
          data['last_failure_at']!,
          _lastFailureAtMeta,
        ),
      );
    }
    if (data.containsKey('is_poisoned')) {
      context.handle(
        _isPoisonedMeta,
        isPoisoned.isAcceptableOrUnknown(data['is_poisoned']!, _isPoisonedMeta),
      );
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {listCode, version};
  @override
  ReferenceListFailure map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return ReferenceListFailure(
      listCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}list_code'],
      )!,
      version: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}version'],
      )!,
      totalFailureCount: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}total_failure_count'],
      )!,
      verificationFailureCount: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}verification_failure_count'],
      )!,
      lastFailureAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}last_failure_at'],
      ),
      isPoisoned: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}is_poisoned'],
      )!,
    );
  }

  @override
  $ReferenceListFailuresTable createAlias(String alias) {
    return $ReferenceListFailuresTable(attachedDatabase, alias);
  }
}

class ReferenceListFailure extends DataClass
    implements Insertable<ReferenceListFailure> {
  final String listCode;
  final int version;
  final int totalFailureCount;
  final int verificationFailureCount;
  final DateTime? lastFailureAt;
  final bool isPoisoned;
  const ReferenceListFailure({
    required this.listCode,
    required this.version,
    required this.totalFailureCount,
    required this.verificationFailureCount,
    this.lastFailureAt,
    required this.isPoisoned,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['list_code'] = Variable<String>(listCode);
    map['version'] = Variable<int>(version);
    map['total_failure_count'] = Variable<int>(totalFailureCount);
    map['verification_failure_count'] = Variable<int>(verificationFailureCount);
    if (!nullToAbsent || lastFailureAt != null) {
      map['last_failure_at'] = Variable<DateTime>(lastFailureAt);
    }
    map['is_poisoned'] = Variable<bool>(isPoisoned);
    return map;
  }

  ReferenceListFailuresCompanion toCompanion(bool nullToAbsent) {
    return ReferenceListFailuresCompanion(
      listCode: Value(listCode),
      version: Value(version),
      totalFailureCount: Value(totalFailureCount),
      verificationFailureCount: Value(verificationFailureCount),
      lastFailureAt: lastFailureAt == null && nullToAbsent
          ? const Value.absent()
          : Value(lastFailureAt),
      isPoisoned: Value(isPoisoned),
    );
  }

  factory ReferenceListFailure.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return ReferenceListFailure(
      listCode: serializer.fromJson<String>(json['listCode']),
      version: serializer.fromJson<int>(json['version']),
      totalFailureCount: serializer.fromJson<int>(json['totalFailureCount']),
      verificationFailureCount: serializer.fromJson<int>(
        json['verificationFailureCount'],
      ),
      lastFailureAt: serializer.fromJson<DateTime?>(json['lastFailureAt']),
      isPoisoned: serializer.fromJson<bool>(json['isPoisoned']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'listCode': serializer.toJson<String>(listCode),
      'version': serializer.toJson<int>(version),
      'totalFailureCount': serializer.toJson<int>(totalFailureCount),
      'verificationFailureCount': serializer.toJson<int>(
        verificationFailureCount,
      ),
      'lastFailureAt': serializer.toJson<DateTime?>(lastFailureAt),
      'isPoisoned': serializer.toJson<bool>(isPoisoned),
    };
  }

  ReferenceListFailure copyWith({
    String? listCode,
    int? version,
    int? totalFailureCount,
    int? verificationFailureCount,
    Value<DateTime?> lastFailureAt = const Value.absent(),
    bool? isPoisoned,
  }) => ReferenceListFailure(
    listCode: listCode ?? this.listCode,
    version: version ?? this.version,
    totalFailureCount: totalFailureCount ?? this.totalFailureCount,
    verificationFailureCount:
        verificationFailureCount ?? this.verificationFailureCount,
    lastFailureAt: lastFailureAt.present
        ? lastFailureAt.value
        : this.lastFailureAt,
    isPoisoned: isPoisoned ?? this.isPoisoned,
  );
  ReferenceListFailure copyWithCompanion(ReferenceListFailuresCompanion data) {
    return ReferenceListFailure(
      listCode: data.listCode.present ? data.listCode.value : this.listCode,
      version: data.version.present ? data.version.value : this.version,
      totalFailureCount: data.totalFailureCount.present
          ? data.totalFailureCount.value
          : this.totalFailureCount,
      verificationFailureCount: data.verificationFailureCount.present
          ? data.verificationFailureCount.value
          : this.verificationFailureCount,
      lastFailureAt: data.lastFailureAt.present
          ? data.lastFailureAt.value
          : this.lastFailureAt,
      isPoisoned: data.isPoisoned.present
          ? data.isPoisoned.value
          : this.isPoisoned,
    );
  }

  @override
  String toString() {
    return (StringBuffer('ReferenceListFailure(')
          ..write('listCode: $listCode, ')
          ..write('version: $version, ')
          ..write('totalFailureCount: $totalFailureCount, ')
          ..write('verificationFailureCount: $verificationFailureCount, ')
          ..write('lastFailureAt: $lastFailureAt, ')
          ..write('isPoisoned: $isPoisoned')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    listCode,
    version,
    totalFailureCount,
    verificationFailureCount,
    lastFailureAt,
    isPoisoned,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is ReferenceListFailure &&
          other.listCode == this.listCode &&
          other.version == this.version &&
          other.totalFailureCount == this.totalFailureCount &&
          other.verificationFailureCount == this.verificationFailureCount &&
          other.lastFailureAt == this.lastFailureAt &&
          other.isPoisoned == this.isPoisoned);
}

class ReferenceListFailuresCompanion
    extends UpdateCompanion<ReferenceListFailure> {
  final Value<String> listCode;
  final Value<int> version;
  final Value<int> totalFailureCount;
  final Value<int> verificationFailureCount;
  final Value<DateTime?> lastFailureAt;
  final Value<bool> isPoisoned;
  final Value<int> rowid;
  const ReferenceListFailuresCompanion({
    this.listCode = const Value.absent(),
    this.version = const Value.absent(),
    this.totalFailureCount = const Value.absent(),
    this.verificationFailureCount = const Value.absent(),
    this.lastFailureAt = const Value.absent(),
    this.isPoisoned = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  ReferenceListFailuresCompanion.insert({
    required String listCode,
    required int version,
    this.totalFailureCount = const Value.absent(),
    this.verificationFailureCount = const Value.absent(),
    this.lastFailureAt = const Value.absent(),
    this.isPoisoned = const Value.absent(),
    this.rowid = const Value.absent(),
  }) : listCode = Value(listCode),
       version = Value(version);
  static Insertable<ReferenceListFailure> custom({
    Expression<String>? listCode,
    Expression<int>? version,
    Expression<int>? totalFailureCount,
    Expression<int>? verificationFailureCount,
    Expression<DateTime>? lastFailureAt,
    Expression<bool>? isPoisoned,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (listCode != null) 'list_code': listCode,
      if (version != null) 'version': version,
      if (totalFailureCount != null) 'total_failure_count': totalFailureCount,
      if (verificationFailureCount != null)
        'verification_failure_count': verificationFailureCount,
      if (lastFailureAt != null) 'last_failure_at': lastFailureAt,
      if (isPoisoned != null) 'is_poisoned': isPoisoned,
      if (rowid != null) 'rowid': rowid,
    });
  }

  ReferenceListFailuresCompanion copyWith({
    Value<String>? listCode,
    Value<int>? version,
    Value<int>? totalFailureCount,
    Value<int>? verificationFailureCount,
    Value<DateTime?>? lastFailureAt,
    Value<bool>? isPoisoned,
    Value<int>? rowid,
  }) {
    return ReferenceListFailuresCompanion(
      listCode: listCode ?? this.listCode,
      version: version ?? this.version,
      totalFailureCount: totalFailureCount ?? this.totalFailureCount,
      verificationFailureCount:
          verificationFailureCount ?? this.verificationFailureCount,
      lastFailureAt: lastFailureAt ?? this.lastFailureAt,
      isPoisoned: isPoisoned ?? this.isPoisoned,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (listCode.present) {
      map['list_code'] = Variable<String>(listCode.value);
    }
    if (version.present) {
      map['version'] = Variable<int>(version.value);
    }
    if (totalFailureCount.present) {
      map['total_failure_count'] = Variable<int>(totalFailureCount.value);
    }
    if (verificationFailureCount.present) {
      map['verification_failure_count'] = Variable<int>(
        verificationFailureCount.value,
      );
    }
    if (lastFailureAt.present) {
      map['last_failure_at'] = Variable<DateTime>(lastFailureAt.value);
    }
    if (isPoisoned.present) {
      map['is_poisoned'] = Variable<bool>(isPoisoned.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('ReferenceListFailuresCompanion(')
          ..write('listCode: $listCode, ')
          ..write('version: $version, ')
          ..write('totalFailureCount: $totalFailureCount, ')
          ..write('verificationFailureCount: $verificationFailureCount, ')
          ..write('lastFailureAt: $lastFailureAt, ')
          ..write('isPoisoned: $isPoisoned, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $ManifestStateTable extends ManifestState
    with TableInfo<$ManifestStateTable, ManifestStateData> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $ManifestStateTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _idMeta = const VerificationMeta('id');
  @override
  late final GeneratedColumn<int> id = GeneratedColumn<int>(
    'id',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
    defaultValue: const Constant(0),
  );
  static const VerificationMeta _catalogHashMeta = const VerificationMeta(
    'catalogHash',
  );
  @override
  late final GeneratedColumn<String> catalogHash = GeneratedColumn<String>(
    'catalog_hash',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _generatedAtMeta = const VerificationMeta(
    'generatedAt',
  );
  @override
  late final GeneratedColumn<DateTime> generatedAt = GeneratedColumn<DateTime>(
    'generated_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _fetchedAtMeta = const VerificationMeta(
    'fetchedAt',
  );
  @override
  late final GeneratedColumn<DateTime> fetchedAt = GeneratedColumn<DateTime>(
    'fetched_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [
    id,
    catalogHash,
    generatedAt,
    fetchedAt,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'manifest_state';
  @override
  VerificationContext validateIntegrity(
    Insertable<ManifestStateData> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    }
    if (data.containsKey('catalog_hash')) {
      context.handle(
        _catalogHashMeta,
        catalogHash.isAcceptableOrUnknown(
          data['catalog_hash']!,
          _catalogHashMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_catalogHashMeta);
    }
    if (data.containsKey('generated_at')) {
      context.handle(
        _generatedAtMeta,
        generatedAt.isAcceptableOrUnknown(
          data['generated_at']!,
          _generatedAtMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_generatedAtMeta);
    }
    if (data.containsKey('fetched_at')) {
      context.handle(
        _fetchedAtMeta,
        fetchedAt.isAcceptableOrUnknown(data['fetched_at']!, _fetchedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_fetchedAtMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  ManifestStateData map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return ManifestStateData(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}id'],
      )!,
      catalogHash: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}catalog_hash'],
      )!,
      generatedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}generated_at'],
      )!,
      fetchedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}fetched_at'],
      )!,
    );
  }

  @override
  $ManifestStateTable createAlias(String alias) {
    return $ManifestStateTable(attachedDatabase, alias);
  }
}

class ManifestStateData extends DataClass
    implements Insertable<ManifestStateData> {
  final int id;
  final String catalogHash;
  final DateTime generatedAt;
  final DateTime fetchedAt;
  const ManifestStateData({
    required this.id,
    required this.catalogHash,
    required this.generatedAt,
    required this.fetchedAt,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<int>(id);
    map['catalog_hash'] = Variable<String>(catalogHash);
    map['generated_at'] = Variable<DateTime>(generatedAt);
    map['fetched_at'] = Variable<DateTime>(fetchedAt);
    return map;
  }

  ManifestStateCompanion toCompanion(bool nullToAbsent) {
    return ManifestStateCompanion(
      id: Value(id),
      catalogHash: Value(catalogHash),
      generatedAt: Value(generatedAt),
      fetchedAt: Value(fetchedAt),
    );
  }

  factory ManifestStateData.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return ManifestStateData(
      id: serializer.fromJson<int>(json['id']),
      catalogHash: serializer.fromJson<String>(json['catalogHash']),
      generatedAt: serializer.fromJson<DateTime>(json['generatedAt']),
      fetchedAt: serializer.fromJson<DateTime>(json['fetchedAt']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<int>(id),
      'catalogHash': serializer.toJson<String>(catalogHash),
      'generatedAt': serializer.toJson<DateTime>(generatedAt),
      'fetchedAt': serializer.toJson<DateTime>(fetchedAt),
    };
  }

  ManifestStateData copyWith({
    int? id,
    String? catalogHash,
    DateTime? generatedAt,
    DateTime? fetchedAt,
  }) => ManifestStateData(
    id: id ?? this.id,
    catalogHash: catalogHash ?? this.catalogHash,
    generatedAt: generatedAt ?? this.generatedAt,
    fetchedAt: fetchedAt ?? this.fetchedAt,
  );
  ManifestStateData copyWithCompanion(ManifestStateCompanion data) {
    return ManifestStateData(
      id: data.id.present ? data.id.value : this.id,
      catalogHash: data.catalogHash.present
          ? data.catalogHash.value
          : this.catalogHash,
      generatedAt: data.generatedAt.present
          ? data.generatedAt.value
          : this.generatedAt,
      fetchedAt: data.fetchedAt.present ? data.fetchedAt.value : this.fetchedAt,
    );
  }

  @override
  String toString() {
    return (StringBuffer('ManifestStateData(')
          ..write('id: $id, ')
          ..write('catalogHash: $catalogHash, ')
          ..write('generatedAt: $generatedAt, ')
          ..write('fetchedAt: $fetchedAt')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(id, catalogHash, generatedAt, fetchedAt);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is ManifestStateData &&
          other.id == this.id &&
          other.catalogHash == this.catalogHash &&
          other.generatedAt == this.generatedAt &&
          other.fetchedAt == this.fetchedAt);
}

class ManifestStateCompanion extends UpdateCompanion<ManifestStateData> {
  final Value<int> id;
  final Value<String> catalogHash;
  final Value<DateTime> generatedAt;
  final Value<DateTime> fetchedAt;
  const ManifestStateCompanion({
    this.id = const Value.absent(),
    this.catalogHash = const Value.absent(),
    this.generatedAt = const Value.absent(),
    this.fetchedAt = const Value.absent(),
  });
  ManifestStateCompanion.insert({
    this.id = const Value.absent(),
    required String catalogHash,
    required DateTime generatedAt,
    required DateTime fetchedAt,
  }) : catalogHash = Value(catalogHash),
       generatedAt = Value(generatedAt),
       fetchedAt = Value(fetchedAt);
  static Insertable<ManifestStateData> custom({
    Expression<int>? id,
    Expression<String>? catalogHash,
    Expression<DateTime>? generatedAt,
    Expression<DateTime>? fetchedAt,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (catalogHash != null) 'catalog_hash': catalogHash,
      if (generatedAt != null) 'generated_at': generatedAt,
      if (fetchedAt != null) 'fetched_at': fetchedAt,
    });
  }

  ManifestStateCompanion copyWith({
    Value<int>? id,
    Value<String>? catalogHash,
    Value<DateTime>? generatedAt,
    Value<DateTime>? fetchedAt,
  }) {
    return ManifestStateCompanion(
      id: id ?? this.id,
      catalogHash: catalogHash ?? this.catalogHash,
      generatedAt: generatedAt ?? this.generatedAt,
      fetchedAt: fetchedAt ?? this.fetchedAt,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<int>(id.value);
    }
    if (catalogHash.present) {
      map['catalog_hash'] = Variable<String>(catalogHash.value);
    }
    if (generatedAt.present) {
      map['generated_at'] = Variable<DateTime>(generatedAt.value);
    }
    if (fetchedAt.present) {
      map['fetched_at'] = Variable<DateTime>(fetchedAt.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('ManifestStateCompanion(')
          ..write('id: $id, ')
          ..write('catalogHash: $catalogHash, ')
          ..write('generatedAt: $generatedAt, ')
          ..write('fetchedAt: $fetchedAt')
          ..write(')'))
        .toString();
  }
}

abstract class _$ReferenceDatabase extends GeneratedDatabase {
  _$ReferenceDatabase(QueryExecutor e) : super(e);
  $ReferenceDatabaseManager get managers => $ReferenceDatabaseManager(this);
  late final $ReferenceListsTable referenceLists = $ReferenceListsTable(this);
  late final $ReferenceItemsTable referenceItems = $ReferenceItemsTable(this);
  late final $ReferenceListFailuresTable referenceListFailures =
      $ReferenceListFailuresTable(this);
  late final $ManifestStateTable manifestState = $ManifestStateTable(this);
  @override
  Iterable<TableInfo<Table, Object?>> get allTables =>
      allSchemaEntities.whereType<TableInfo<Table, Object?>>();
  @override
  List<DatabaseSchemaEntity> get allSchemaEntities => [
    referenceLists,
    referenceItems,
    referenceListFailures,
    manifestState,
  ];
}

typedef $$ReferenceListsTableCreateCompanionBuilder =
    ReferenceListsCompanion Function({
      required String listCode,
      required int version,
      required String contentHash,
      required int itemCount,
      required bool isHierarchical,
      Value<String?> rootItemCode,
      Value<int?> rootCountryVersion,
      required String nameAr,
      required String nameEn,
      required DateTime publishedAt,
      required DateTime fetchedAt,
      Value<bool> isActiveVersion,
      Value<int> rowid,
    });
typedef $$ReferenceListsTableUpdateCompanionBuilder =
    ReferenceListsCompanion Function({
      Value<String> listCode,
      Value<int> version,
      Value<String> contentHash,
      Value<int> itemCount,
      Value<bool> isHierarchical,
      Value<String?> rootItemCode,
      Value<int?> rootCountryVersion,
      Value<String> nameAr,
      Value<String> nameEn,
      Value<DateTime> publishedAt,
      Value<DateTime> fetchedAt,
      Value<bool> isActiveVersion,
      Value<int> rowid,
    });

class $$ReferenceListsTableFilterComposer
    extends Composer<_$ReferenceDatabase, $ReferenceListsTable> {
  $$ReferenceListsTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get listCode => $composableBuilder(
    column: $table.listCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get version => $composableBuilder(
    column: $table.version,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get contentHash => $composableBuilder(
    column: $table.contentHash,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get itemCount => $composableBuilder(
    column: $table.itemCount,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get isHierarchical => $composableBuilder(
    column: $table.isHierarchical,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get rootItemCode => $composableBuilder(
    column: $table.rootItemCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get rootCountryVersion => $composableBuilder(
    column: $table.rootCountryVersion,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get nameAr => $composableBuilder(
    column: $table.nameAr,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get nameEn => $composableBuilder(
    column: $table.nameEn,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get publishedAt => $composableBuilder(
    column: $table.publishedAt,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get fetchedAt => $composableBuilder(
    column: $table.fetchedAt,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get isActiveVersion => $composableBuilder(
    column: $table.isActiveVersion,
    builder: (column) => ColumnFilters(column),
  );
}

class $$ReferenceListsTableOrderingComposer
    extends Composer<_$ReferenceDatabase, $ReferenceListsTable> {
  $$ReferenceListsTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get listCode => $composableBuilder(
    column: $table.listCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get version => $composableBuilder(
    column: $table.version,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get contentHash => $composableBuilder(
    column: $table.contentHash,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get itemCount => $composableBuilder(
    column: $table.itemCount,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get isHierarchical => $composableBuilder(
    column: $table.isHierarchical,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get rootItemCode => $composableBuilder(
    column: $table.rootItemCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get rootCountryVersion => $composableBuilder(
    column: $table.rootCountryVersion,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get nameAr => $composableBuilder(
    column: $table.nameAr,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get nameEn => $composableBuilder(
    column: $table.nameEn,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get publishedAt => $composableBuilder(
    column: $table.publishedAt,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get fetchedAt => $composableBuilder(
    column: $table.fetchedAt,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get isActiveVersion => $composableBuilder(
    column: $table.isActiveVersion,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$ReferenceListsTableAnnotationComposer
    extends Composer<_$ReferenceDatabase, $ReferenceListsTable> {
  $$ReferenceListsTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get listCode =>
      $composableBuilder(column: $table.listCode, builder: (column) => column);

  GeneratedColumn<int> get version =>
      $composableBuilder(column: $table.version, builder: (column) => column);

  GeneratedColumn<String> get contentHash => $composableBuilder(
    column: $table.contentHash,
    builder: (column) => column,
  );

  GeneratedColumn<int> get itemCount =>
      $composableBuilder(column: $table.itemCount, builder: (column) => column);

  GeneratedColumn<bool> get isHierarchical => $composableBuilder(
    column: $table.isHierarchical,
    builder: (column) => column,
  );

  GeneratedColumn<String> get rootItemCode => $composableBuilder(
    column: $table.rootItemCode,
    builder: (column) => column,
  );

  GeneratedColumn<int> get rootCountryVersion => $composableBuilder(
    column: $table.rootCountryVersion,
    builder: (column) => column,
  );

  GeneratedColumn<String> get nameAr =>
      $composableBuilder(column: $table.nameAr, builder: (column) => column);

  GeneratedColumn<String> get nameEn =>
      $composableBuilder(column: $table.nameEn, builder: (column) => column);

  GeneratedColumn<DateTime> get publishedAt => $composableBuilder(
    column: $table.publishedAt,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get fetchedAt =>
      $composableBuilder(column: $table.fetchedAt, builder: (column) => column);

  GeneratedColumn<bool> get isActiveVersion => $composableBuilder(
    column: $table.isActiveVersion,
    builder: (column) => column,
  );
}

class $$ReferenceListsTableTableManager
    extends
        RootTableManager<
          _$ReferenceDatabase,
          $ReferenceListsTable,
          ReferenceList,
          $$ReferenceListsTableFilterComposer,
          $$ReferenceListsTableOrderingComposer,
          $$ReferenceListsTableAnnotationComposer,
          $$ReferenceListsTableCreateCompanionBuilder,
          $$ReferenceListsTableUpdateCompanionBuilder,
          (
            ReferenceList,
            BaseReferences<
              _$ReferenceDatabase,
              $ReferenceListsTable,
              ReferenceList
            >,
          ),
          ReferenceList,
          PrefetchHooks Function()
        > {
  $$ReferenceListsTableTableManager(
    _$ReferenceDatabase db,
    $ReferenceListsTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$ReferenceListsTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$ReferenceListsTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$ReferenceListsTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String> listCode = const Value.absent(),
                Value<int> version = const Value.absent(),
                Value<String> contentHash = const Value.absent(),
                Value<int> itemCount = const Value.absent(),
                Value<bool> isHierarchical = const Value.absent(),
                Value<String?> rootItemCode = const Value.absent(),
                Value<int?> rootCountryVersion = const Value.absent(),
                Value<String> nameAr = const Value.absent(),
                Value<String> nameEn = const Value.absent(),
                Value<DateTime> publishedAt = const Value.absent(),
                Value<DateTime> fetchedAt = const Value.absent(),
                Value<bool> isActiveVersion = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ReferenceListsCompanion(
                listCode: listCode,
                version: version,
                contentHash: contentHash,
                itemCount: itemCount,
                isHierarchical: isHierarchical,
                rootItemCode: rootItemCode,
                rootCountryVersion: rootCountryVersion,
                nameAr: nameAr,
                nameEn: nameEn,
                publishedAt: publishedAt,
                fetchedAt: fetchedAt,
                isActiveVersion: isActiveVersion,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String listCode,
                required int version,
                required String contentHash,
                required int itemCount,
                required bool isHierarchical,
                Value<String?> rootItemCode = const Value.absent(),
                Value<int?> rootCountryVersion = const Value.absent(),
                required String nameAr,
                required String nameEn,
                required DateTime publishedAt,
                required DateTime fetchedAt,
                Value<bool> isActiveVersion = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ReferenceListsCompanion.insert(
                listCode: listCode,
                version: version,
                contentHash: contentHash,
                itemCount: itemCount,
                isHierarchical: isHierarchical,
                rootItemCode: rootItemCode,
                rootCountryVersion: rootCountryVersion,
                nameAr: nameAr,
                nameEn: nameEn,
                publishedAt: publishedAt,
                fetchedAt: fetchedAt,
                isActiveVersion: isActiveVersion,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$ReferenceListsTableProcessedTableManager =
    ProcessedTableManager<
      _$ReferenceDatabase,
      $ReferenceListsTable,
      ReferenceList,
      $$ReferenceListsTableFilterComposer,
      $$ReferenceListsTableOrderingComposer,
      $$ReferenceListsTableAnnotationComposer,
      $$ReferenceListsTableCreateCompanionBuilder,
      $$ReferenceListsTableUpdateCompanionBuilder,
      (
        ReferenceList,
        BaseReferences<
          _$ReferenceDatabase,
          $ReferenceListsTable,
          ReferenceList
        >,
      ),
      ReferenceList,
      PrefetchHooks Function()
    >;
typedef $$ReferenceItemsTableCreateCompanionBuilder =
    ReferenceItemsCompanion Function({
      required String listCode,
      required int version,
      required String itemCode,
      Value<String?> parentCode,
      required String labelAr,
      Value<String?> labelEn,
      required String searchAr,
      Value<String?> searchEn,
      required int sortOrdinal,
      required bool isActive,
      Value<String?> extraJson,
      Value<int> rowid,
    });
typedef $$ReferenceItemsTableUpdateCompanionBuilder =
    ReferenceItemsCompanion Function({
      Value<String> listCode,
      Value<int> version,
      Value<String> itemCode,
      Value<String?> parentCode,
      Value<String> labelAr,
      Value<String?> labelEn,
      Value<String> searchAr,
      Value<String?> searchEn,
      Value<int> sortOrdinal,
      Value<bool> isActive,
      Value<String?> extraJson,
      Value<int> rowid,
    });

class $$ReferenceItemsTableFilterComposer
    extends Composer<_$ReferenceDatabase, $ReferenceItemsTable> {
  $$ReferenceItemsTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get listCode => $composableBuilder(
    column: $table.listCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get version => $composableBuilder(
    column: $table.version,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get itemCode => $composableBuilder(
    column: $table.itemCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get parentCode => $composableBuilder(
    column: $table.parentCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get labelAr => $composableBuilder(
    column: $table.labelAr,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get labelEn => $composableBuilder(
    column: $table.labelEn,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get searchAr => $composableBuilder(
    column: $table.searchAr,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get searchEn => $composableBuilder(
    column: $table.searchEn,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get sortOrdinal => $composableBuilder(
    column: $table.sortOrdinal,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get isActive => $composableBuilder(
    column: $table.isActive,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get extraJson => $composableBuilder(
    column: $table.extraJson,
    builder: (column) => ColumnFilters(column),
  );
}

class $$ReferenceItemsTableOrderingComposer
    extends Composer<_$ReferenceDatabase, $ReferenceItemsTable> {
  $$ReferenceItemsTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get listCode => $composableBuilder(
    column: $table.listCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get version => $composableBuilder(
    column: $table.version,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get itemCode => $composableBuilder(
    column: $table.itemCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get parentCode => $composableBuilder(
    column: $table.parentCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get labelAr => $composableBuilder(
    column: $table.labelAr,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get labelEn => $composableBuilder(
    column: $table.labelEn,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get searchAr => $composableBuilder(
    column: $table.searchAr,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get searchEn => $composableBuilder(
    column: $table.searchEn,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get sortOrdinal => $composableBuilder(
    column: $table.sortOrdinal,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get isActive => $composableBuilder(
    column: $table.isActive,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get extraJson => $composableBuilder(
    column: $table.extraJson,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$ReferenceItemsTableAnnotationComposer
    extends Composer<_$ReferenceDatabase, $ReferenceItemsTable> {
  $$ReferenceItemsTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get listCode =>
      $composableBuilder(column: $table.listCode, builder: (column) => column);

  GeneratedColumn<int> get version =>
      $composableBuilder(column: $table.version, builder: (column) => column);

  GeneratedColumn<String> get itemCode =>
      $composableBuilder(column: $table.itemCode, builder: (column) => column);

  GeneratedColumn<String> get parentCode => $composableBuilder(
    column: $table.parentCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get labelAr =>
      $composableBuilder(column: $table.labelAr, builder: (column) => column);

  GeneratedColumn<String> get labelEn =>
      $composableBuilder(column: $table.labelEn, builder: (column) => column);

  GeneratedColumn<String> get searchAr =>
      $composableBuilder(column: $table.searchAr, builder: (column) => column);

  GeneratedColumn<String> get searchEn =>
      $composableBuilder(column: $table.searchEn, builder: (column) => column);

  GeneratedColumn<int> get sortOrdinal => $composableBuilder(
    column: $table.sortOrdinal,
    builder: (column) => column,
  );

  GeneratedColumn<bool> get isActive =>
      $composableBuilder(column: $table.isActive, builder: (column) => column);

  GeneratedColumn<String> get extraJson =>
      $composableBuilder(column: $table.extraJson, builder: (column) => column);
}

class $$ReferenceItemsTableTableManager
    extends
        RootTableManager<
          _$ReferenceDatabase,
          $ReferenceItemsTable,
          ReferenceItem,
          $$ReferenceItemsTableFilterComposer,
          $$ReferenceItemsTableOrderingComposer,
          $$ReferenceItemsTableAnnotationComposer,
          $$ReferenceItemsTableCreateCompanionBuilder,
          $$ReferenceItemsTableUpdateCompanionBuilder,
          (
            ReferenceItem,
            BaseReferences<
              _$ReferenceDatabase,
              $ReferenceItemsTable,
              ReferenceItem
            >,
          ),
          ReferenceItem,
          PrefetchHooks Function()
        > {
  $$ReferenceItemsTableTableManager(
    _$ReferenceDatabase db,
    $ReferenceItemsTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$ReferenceItemsTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$ReferenceItemsTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$ReferenceItemsTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String> listCode = const Value.absent(),
                Value<int> version = const Value.absent(),
                Value<String> itemCode = const Value.absent(),
                Value<String?> parentCode = const Value.absent(),
                Value<String> labelAr = const Value.absent(),
                Value<String?> labelEn = const Value.absent(),
                Value<String> searchAr = const Value.absent(),
                Value<String?> searchEn = const Value.absent(),
                Value<int> sortOrdinal = const Value.absent(),
                Value<bool> isActive = const Value.absent(),
                Value<String?> extraJson = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ReferenceItemsCompanion(
                listCode: listCode,
                version: version,
                itemCode: itemCode,
                parentCode: parentCode,
                labelAr: labelAr,
                labelEn: labelEn,
                searchAr: searchAr,
                searchEn: searchEn,
                sortOrdinal: sortOrdinal,
                isActive: isActive,
                extraJson: extraJson,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String listCode,
                required int version,
                required String itemCode,
                Value<String?> parentCode = const Value.absent(),
                required String labelAr,
                Value<String?> labelEn = const Value.absent(),
                required String searchAr,
                Value<String?> searchEn = const Value.absent(),
                required int sortOrdinal,
                required bool isActive,
                Value<String?> extraJson = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ReferenceItemsCompanion.insert(
                listCode: listCode,
                version: version,
                itemCode: itemCode,
                parentCode: parentCode,
                labelAr: labelAr,
                labelEn: labelEn,
                searchAr: searchAr,
                searchEn: searchEn,
                sortOrdinal: sortOrdinal,
                isActive: isActive,
                extraJson: extraJson,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$ReferenceItemsTableProcessedTableManager =
    ProcessedTableManager<
      _$ReferenceDatabase,
      $ReferenceItemsTable,
      ReferenceItem,
      $$ReferenceItemsTableFilterComposer,
      $$ReferenceItemsTableOrderingComposer,
      $$ReferenceItemsTableAnnotationComposer,
      $$ReferenceItemsTableCreateCompanionBuilder,
      $$ReferenceItemsTableUpdateCompanionBuilder,
      (
        ReferenceItem,
        BaseReferences<
          _$ReferenceDatabase,
          $ReferenceItemsTable,
          ReferenceItem
        >,
      ),
      ReferenceItem,
      PrefetchHooks Function()
    >;
typedef $$ReferenceListFailuresTableCreateCompanionBuilder =
    ReferenceListFailuresCompanion Function({
      required String listCode,
      required int version,
      Value<int> totalFailureCount,
      Value<int> verificationFailureCount,
      Value<DateTime?> lastFailureAt,
      Value<bool> isPoisoned,
      Value<int> rowid,
    });
typedef $$ReferenceListFailuresTableUpdateCompanionBuilder =
    ReferenceListFailuresCompanion Function({
      Value<String> listCode,
      Value<int> version,
      Value<int> totalFailureCount,
      Value<int> verificationFailureCount,
      Value<DateTime?> lastFailureAt,
      Value<bool> isPoisoned,
      Value<int> rowid,
    });

class $$ReferenceListFailuresTableFilterComposer
    extends Composer<_$ReferenceDatabase, $ReferenceListFailuresTable> {
  $$ReferenceListFailuresTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get listCode => $composableBuilder(
    column: $table.listCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get version => $composableBuilder(
    column: $table.version,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get totalFailureCount => $composableBuilder(
    column: $table.totalFailureCount,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get verificationFailureCount => $composableBuilder(
    column: $table.verificationFailureCount,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get lastFailureAt => $composableBuilder(
    column: $table.lastFailureAt,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get isPoisoned => $composableBuilder(
    column: $table.isPoisoned,
    builder: (column) => ColumnFilters(column),
  );
}

class $$ReferenceListFailuresTableOrderingComposer
    extends Composer<_$ReferenceDatabase, $ReferenceListFailuresTable> {
  $$ReferenceListFailuresTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get listCode => $composableBuilder(
    column: $table.listCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get version => $composableBuilder(
    column: $table.version,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get totalFailureCount => $composableBuilder(
    column: $table.totalFailureCount,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get verificationFailureCount => $composableBuilder(
    column: $table.verificationFailureCount,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get lastFailureAt => $composableBuilder(
    column: $table.lastFailureAt,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get isPoisoned => $composableBuilder(
    column: $table.isPoisoned,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$ReferenceListFailuresTableAnnotationComposer
    extends Composer<_$ReferenceDatabase, $ReferenceListFailuresTable> {
  $$ReferenceListFailuresTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get listCode =>
      $composableBuilder(column: $table.listCode, builder: (column) => column);

  GeneratedColumn<int> get version =>
      $composableBuilder(column: $table.version, builder: (column) => column);

  GeneratedColumn<int> get totalFailureCount => $composableBuilder(
    column: $table.totalFailureCount,
    builder: (column) => column,
  );

  GeneratedColumn<int> get verificationFailureCount => $composableBuilder(
    column: $table.verificationFailureCount,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get lastFailureAt => $composableBuilder(
    column: $table.lastFailureAt,
    builder: (column) => column,
  );

  GeneratedColumn<bool> get isPoisoned => $composableBuilder(
    column: $table.isPoisoned,
    builder: (column) => column,
  );
}

class $$ReferenceListFailuresTableTableManager
    extends
        RootTableManager<
          _$ReferenceDatabase,
          $ReferenceListFailuresTable,
          ReferenceListFailure,
          $$ReferenceListFailuresTableFilterComposer,
          $$ReferenceListFailuresTableOrderingComposer,
          $$ReferenceListFailuresTableAnnotationComposer,
          $$ReferenceListFailuresTableCreateCompanionBuilder,
          $$ReferenceListFailuresTableUpdateCompanionBuilder,
          (
            ReferenceListFailure,
            BaseReferences<
              _$ReferenceDatabase,
              $ReferenceListFailuresTable,
              ReferenceListFailure
            >,
          ),
          ReferenceListFailure,
          PrefetchHooks Function()
        > {
  $$ReferenceListFailuresTableTableManager(
    _$ReferenceDatabase db,
    $ReferenceListFailuresTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$ReferenceListFailuresTableFilterComposer(
                $db: db,
                $table: table,
              ),
          createOrderingComposer: () =>
              $$ReferenceListFailuresTableOrderingComposer(
                $db: db,
                $table: table,
              ),
          createComputedFieldComposer: () =>
              $$ReferenceListFailuresTableAnnotationComposer(
                $db: db,
                $table: table,
              ),
          updateCompanionCallback:
              ({
                Value<String> listCode = const Value.absent(),
                Value<int> version = const Value.absent(),
                Value<int> totalFailureCount = const Value.absent(),
                Value<int> verificationFailureCount = const Value.absent(),
                Value<DateTime?> lastFailureAt = const Value.absent(),
                Value<bool> isPoisoned = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ReferenceListFailuresCompanion(
                listCode: listCode,
                version: version,
                totalFailureCount: totalFailureCount,
                verificationFailureCount: verificationFailureCount,
                lastFailureAt: lastFailureAt,
                isPoisoned: isPoisoned,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String listCode,
                required int version,
                Value<int> totalFailureCount = const Value.absent(),
                Value<int> verificationFailureCount = const Value.absent(),
                Value<DateTime?> lastFailureAt = const Value.absent(),
                Value<bool> isPoisoned = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => ReferenceListFailuresCompanion.insert(
                listCode: listCode,
                version: version,
                totalFailureCount: totalFailureCount,
                verificationFailureCount: verificationFailureCount,
                lastFailureAt: lastFailureAt,
                isPoisoned: isPoisoned,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$ReferenceListFailuresTableProcessedTableManager =
    ProcessedTableManager<
      _$ReferenceDatabase,
      $ReferenceListFailuresTable,
      ReferenceListFailure,
      $$ReferenceListFailuresTableFilterComposer,
      $$ReferenceListFailuresTableOrderingComposer,
      $$ReferenceListFailuresTableAnnotationComposer,
      $$ReferenceListFailuresTableCreateCompanionBuilder,
      $$ReferenceListFailuresTableUpdateCompanionBuilder,
      (
        ReferenceListFailure,
        BaseReferences<
          _$ReferenceDatabase,
          $ReferenceListFailuresTable,
          ReferenceListFailure
        >,
      ),
      ReferenceListFailure,
      PrefetchHooks Function()
    >;
typedef $$ManifestStateTableCreateCompanionBuilder =
    ManifestStateCompanion Function({
      Value<int> id,
      required String catalogHash,
      required DateTime generatedAt,
      required DateTime fetchedAt,
    });
typedef $$ManifestStateTableUpdateCompanionBuilder =
    ManifestStateCompanion Function({
      Value<int> id,
      Value<String> catalogHash,
      Value<DateTime> generatedAt,
      Value<DateTime> fetchedAt,
    });

class $$ManifestStateTableFilterComposer
    extends Composer<_$ReferenceDatabase, $ManifestStateTable> {
  $$ManifestStateTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<int> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get catalogHash => $composableBuilder(
    column: $table.catalogHash,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get generatedAt => $composableBuilder(
    column: $table.generatedAt,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get fetchedAt => $composableBuilder(
    column: $table.fetchedAt,
    builder: (column) => ColumnFilters(column),
  );
}

class $$ManifestStateTableOrderingComposer
    extends Composer<_$ReferenceDatabase, $ManifestStateTable> {
  $$ManifestStateTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<int> get id => $composableBuilder(
    column: $table.id,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get catalogHash => $composableBuilder(
    column: $table.catalogHash,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get generatedAt => $composableBuilder(
    column: $table.generatedAt,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get fetchedAt => $composableBuilder(
    column: $table.fetchedAt,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$ManifestStateTableAnnotationComposer
    extends Composer<_$ReferenceDatabase, $ManifestStateTable> {
  $$ManifestStateTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<int> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get catalogHash => $composableBuilder(
    column: $table.catalogHash,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get generatedAt => $composableBuilder(
    column: $table.generatedAt,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get fetchedAt =>
      $composableBuilder(column: $table.fetchedAt, builder: (column) => column);
}

class $$ManifestStateTableTableManager
    extends
        RootTableManager<
          _$ReferenceDatabase,
          $ManifestStateTable,
          ManifestStateData,
          $$ManifestStateTableFilterComposer,
          $$ManifestStateTableOrderingComposer,
          $$ManifestStateTableAnnotationComposer,
          $$ManifestStateTableCreateCompanionBuilder,
          $$ManifestStateTableUpdateCompanionBuilder,
          (
            ManifestStateData,
            BaseReferences<
              _$ReferenceDatabase,
              $ManifestStateTable,
              ManifestStateData
            >,
          ),
          ManifestStateData,
          PrefetchHooks Function()
        > {
  $$ManifestStateTableTableManager(
    _$ReferenceDatabase db,
    $ManifestStateTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$ManifestStateTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$ManifestStateTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$ManifestStateTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                Value<String> catalogHash = const Value.absent(),
                Value<DateTime> generatedAt = const Value.absent(),
                Value<DateTime> fetchedAt = const Value.absent(),
              }) => ManifestStateCompanion(
                id: id,
                catalogHash: catalogHash,
                generatedAt: generatedAt,
                fetchedAt: fetchedAt,
              ),
          createCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                required String catalogHash,
                required DateTime generatedAt,
                required DateTime fetchedAt,
              }) => ManifestStateCompanion.insert(
                id: id,
                catalogHash: catalogHash,
                generatedAt: generatedAt,
                fetchedAt: fetchedAt,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$ManifestStateTableProcessedTableManager =
    ProcessedTableManager<
      _$ReferenceDatabase,
      $ManifestStateTable,
      ManifestStateData,
      $$ManifestStateTableFilterComposer,
      $$ManifestStateTableOrderingComposer,
      $$ManifestStateTableAnnotationComposer,
      $$ManifestStateTableCreateCompanionBuilder,
      $$ManifestStateTableUpdateCompanionBuilder,
      (
        ManifestStateData,
        BaseReferences<
          _$ReferenceDatabase,
          $ManifestStateTable,
          ManifestStateData
        >,
      ),
      ManifestStateData,
      PrefetchHooks Function()
    >;

class $ReferenceDatabaseManager {
  final _$ReferenceDatabase _db;
  $ReferenceDatabaseManager(this._db);
  $$ReferenceListsTableTableManager get referenceLists =>
      $$ReferenceListsTableTableManager(_db, _db.referenceLists);
  $$ReferenceItemsTableTableManager get referenceItems =>
      $$ReferenceItemsTableTableManager(_db, _db.referenceItems);
  $$ReferenceListFailuresTableTableManager get referenceListFailures =>
      $$ReferenceListFailuresTableTableManager(_db, _db.referenceListFailures);
  $$ManifestStateTableTableManager get manifestState =>
      $$ManifestStateTableTableManager(_db, _db.manifestState);
}

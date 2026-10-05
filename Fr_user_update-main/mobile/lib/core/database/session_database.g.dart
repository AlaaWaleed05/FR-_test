// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'session_database.dart';

// ignore_for_file: type=lint
class $PinnedReferenceVersionsTable extends PinnedReferenceVersions
    with TableInfo<$PinnedReferenceVersionsTable, PinnedReferenceVersion> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $PinnedReferenceVersionsTable(this.attachedDatabase, [this._alias]);
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
  static const VerificationMeta _pinnedVersionMeta = const VerificationMeta(
    'pinnedVersion',
  );
  @override
  late final GeneratedColumn<int> pinnedVersion = GeneratedColumn<int>(
    'pinned_version',
    aliasedName,
    false,
    type: DriftSqlType.int,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _pinnedAtMeta = const VerificationMeta(
    'pinnedAt',
  );
  @override
  late final GeneratedColumn<DateTime> pinnedAt = GeneratedColumn<DateTime>(
    'pinned_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [listCode, pinnedVersion, pinnedAt];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'pinned_reference_versions';
  @override
  VerificationContext validateIntegrity(
    Insertable<PinnedReferenceVersion> instance, {
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
    if (data.containsKey('pinned_version')) {
      context.handle(
        _pinnedVersionMeta,
        pinnedVersion.isAcceptableOrUnknown(
          data['pinned_version']!,
          _pinnedVersionMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_pinnedVersionMeta);
    }
    if (data.containsKey('pinned_at')) {
      context.handle(
        _pinnedAtMeta,
        pinnedAt.isAcceptableOrUnknown(data['pinned_at']!, _pinnedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_pinnedAtMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {listCode};
  @override
  PinnedReferenceVersion map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return PinnedReferenceVersion(
      listCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}list_code'],
      )!,
      pinnedVersion: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}pinned_version'],
      )!,
      pinnedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}pinned_at'],
      )!,
    );
  }

  @override
  $PinnedReferenceVersionsTable createAlias(String alias) {
    return $PinnedReferenceVersionsTable(attachedDatabase, alias);
  }
}

class PinnedReferenceVersion extends DataClass
    implements Insertable<PinnedReferenceVersion> {
  final String listCode;
  final int pinnedVersion;
  final DateTime pinnedAt;
  const PinnedReferenceVersion({
    required this.listCode,
    required this.pinnedVersion,
    required this.pinnedAt,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['list_code'] = Variable<String>(listCode);
    map['pinned_version'] = Variable<int>(pinnedVersion);
    map['pinned_at'] = Variable<DateTime>(pinnedAt);
    return map;
  }

  PinnedReferenceVersionsCompanion toCompanion(bool nullToAbsent) {
    return PinnedReferenceVersionsCompanion(
      listCode: Value(listCode),
      pinnedVersion: Value(pinnedVersion),
      pinnedAt: Value(pinnedAt),
    );
  }

  factory PinnedReferenceVersion.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return PinnedReferenceVersion(
      listCode: serializer.fromJson<String>(json['listCode']),
      pinnedVersion: serializer.fromJson<int>(json['pinnedVersion']),
      pinnedAt: serializer.fromJson<DateTime>(json['pinnedAt']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'listCode': serializer.toJson<String>(listCode),
      'pinnedVersion': serializer.toJson<int>(pinnedVersion),
      'pinnedAt': serializer.toJson<DateTime>(pinnedAt),
    };
  }

  PinnedReferenceVersion copyWith({
    String? listCode,
    int? pinnedVersion,
    DateTime? pinnedAt,
  }) => PinnedReferenceVersion(
    listCode: listCode ?? this.listCode,
    pinnedVersion: pinnedVersion ?? this.pinnedVersion,
    pinnedAt: pinnedAt ?? this.pinnedAt,
  );
  PinnedReferenceVersion copyWithCompanion(
    PinnedReferenceVersionsCompanion data,
  ) {
    return PinnedReferenceVersion(
      listCode: data.listCode.present ? data.listCode.value : this.listCode,
      pinnedVersion: data.pinnedVersion.present
          ? data.pinnedVersion.value
          : this.pinnedVersion,
      pinnedAt: data.pinnedAt.present ? data.pinnedAt.value : this.pinnedAt,
    );
  }

  @override
  String toString() {
    return (StringBuffer('PinnedReferenceVersion(')
          ..write('listCode: $listCode, ')
          ..write('pinnedVersion: $pinnedVersion, ')
          ..write('pinnedAt: $pinnedAt')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(listCode, pinnedVersion, pinnedAt);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is PinnedReferenceVersion &&
          other.listCode == this.listCode &&
          other.pinnedVersion == this.pinnedVersion &&
          other.pinnedAt == this.pinnedAt);
}

class PinnedReferenceVersionsCompanion
    extends UpdateCompanion<PinnedReferenceVersion> {
  final Value<String> listCode;
  final Value<int> pinnedVersion;
  final Value<DateTime> pinnedAt;
  final Value<int> rowid;
  const PinnedReferenceVersionsCompanion({
    this.listCode = const Value.absent(),
    this.pinnedVersion = const Value.absent(),
    this.pinnedAt = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  PinnedReferenceVersionsCompanion.insert({
    required String listCode,
    required int pinnedVersion,
    required DateTime pinnedAt,
    this.rowid = const Value.absent(),
  }) : listCode = Value(listCode),
       pinnedVersion = Value(pinnedVersion),
       pinnedAt = Value(pinnedAt);
  static Insertable<PinnedReferenceVersion> custom({
    Expression<String>? listCode,
    Expression<int>? pinnedVersion,
    Expression<DateTime>? pinnedAt,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (listCode != null) 'list_code': listCode,
      if (pinnedVersion != null) 'pinned_version': pinnedVersion,
      if (pinnedAt != null) 'pinned_at': pinnedAt,
      if (rowid != null) 'rowid': rowid,
    });
  }

  PinnedReferenceVersionsCompanion copyWith({
    Value<String>? listCode,
    Value<int>? pinnedVersion,
    Value<DateTime>? pinnedAt,
    Value<int>? rowid,
  }) {
    return PinnedReferenceVersionsCompanion(
      listCode: listCode ?? this.listCode,
      pinnedVersion: pinnedVersion ?? this.pinnedVersion,
      pinnedAt: pinnedAt ?? this.pinnedAt,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (listCode.present) {
      map['list_code'] = Variable<String>(listCode.value);
    }
    if (pinnedVersion.present) {
      map['pinned_version'] = Variable<int>(pinnedVersion.value);
    }
    if (pinnedAt.present) {
      map['pinned_at'] = Variable<DateTime>(pinnedAt.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('PinnedReferenceVersionsCompanion(')
          ..write('listCode: $listCode, ')
          ..write('pinnedVersion: $pinnedVersion, ')
          ..write('pinnedAt: $pinnedAt, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $LocalDraftTable extends LocalDraft
    with TableInfo<$LocalDraftTable, LocalDraftData> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $LocalDraftTable(this.attachedDatabase, [this._alias]);
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
  static const VerificationMeta _branchCodeMeta = const VerificationMeta(
    'branchCode',
  );
  @override
  late final GeneratedColumn<String> branchCode = GeneratedColumn<String>(
    'branch_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _accountNumberMeta = const VerificationMeta(
    'accountNumber',
  );
  @override
  late final GeneratedColumn<String> accountNumber = GeneratedColumn<String>(
    'account_number',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _phoneNumberMeta = const VerificationMeta(
    'phoneNumber',
  );
  @override
  late final GeneratedColumn<String> phoneNumber = GeneratedColumn<String>(
    'phone_number',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _smsSelectedMeta = const VerificationMeta(
    'smsSelected',
  );
  @override
  late final GeneratedColumn<bool> smsSelected = GeneratedColumn<bool>(
    'sms_selected',
    aliasedName,
    false,
    type: DriftSqlType.bool,
    requiredDuringInsert: false,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("sms_selected" IN (0, 1))',
    ),
    defaultValue: const Constant(true),
  );
  static const VerificationMeta _whatsappSelectedMeta = const VerificationMeta(
    'whatsappSelected',
  );
  @override
  late final GeneratedColumn<bool> whatsappSelected = GeneratedColumn<bool>(
    'whatsapp_selected',
    aliasedName,
    false,
    type: DriftSqlType.bool,
    requiredDuringInsert: false,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("whatsapp_selected" IN (0, 1))',
    ),
    defaultValue: const Constant(false),
  );
  static const VerificationMeta _emailAddressMeta = const VerificationMeta(
    'emailAddress',
  );
  @override
  late final GeneratedColumn<String> emailAddress = GeneratedColumn<String>(
    'email_address',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _emailSelectedMeta = const VerificationMeta(
    'emailSelected',
  );
  @override
  late final GeneratedColumn<bool> emailSelected = GeneratedColumn<bool>(
    'email_selected',
    aliasedName,
    false,
    type: DriftSqlType.bool,
    requiredDuringInsert: false,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("email_selected" IN (0, 1))',
    ),
    defaultValue: const Constant(true),
  );
  static const VerificationMeta _updatedAtMeta = const VerificationMeta(
    'updatedAt',
  );
  @override
  late final GeneratedColumn<DateTime> updatedAt = GeneratedColumn<DateTime>(
    'updated_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [
    id,
    branchCode,
    accountNumber,
    phoneNumber,
    smsSelected,
    whatsappSelected,
    emailAddress,
    emailSelected,
    updatedAt,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'local_draft';
  @override
  VerificationContext validateIntegrity(
    Insertable<LocalDraftData> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    }
    if (data.containsKey('branch_code')) {
      context.handle(
        _branchCodeMeta,
        branchCode.isAcceptableOrUnknown(data['branch_code']!, _branchCodeMeta),
      );
    }
    if (data.containsKey('account_number')) {
      context.handle(
        _accountNumberMeta,
        accountNumber.isAcceptableOrUnknown(
          data['account_number']!,
          _accountNumberMeta,
        ),
      );
    }
    if (data.containsKey('phone_number')) {
      context.handle(
        _phoneNumberMeta,
        phoneNumber.isAcceptableOrUnknown(
          data['phone_number']!,
          _phoneNumberMeta,
        ),
      );
    }
    if (data.containsKey('sms_selected')) {
      context.handle(
        _smsSelectedMeta,
        smsSelected.isAcceptableOrUnknown(
          data['sms_selected']!,
          _smsSelectedMeta,
        ),
      );
    }
    if (data.containsKey('whatsapp_selected')) {
      context.handle(
        _whatsappSelectedMeta,
        whatsappSelected.isAcceptableOrUnknown(
          data['whatsapp_selected']!,
          _whatsappSelectedMeta,
        ),
      );
    }
    if (data.containsKey('email_address')) {
      context.handle(
        _emailAddressMeta,
        emailAddress.isAcceptableOrUnknown(
          data['email_address']!,
          _emailAddressMeta,
        ),
      );
    }
    if (data.containsKey('email_selected')) {
      context.handle(
        _emailSelectedMeta,
        emailSelected.isAcceptableOrUnknown(
          data['email_selected']!,
          _emailSelectedMeta,
        ),
      );
    }
    if (data.containsKey('updated_at')) {
      context.handle(
        _updatedAtMeta,
        updatedAt.isAcceptableOrUnknown(data['updated_at']!, _updatedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_updatedAtMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  LocalDraftData map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return LocalDraftData(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}id'],
      )!,
      branchCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}branch_code'],
      ),
      accountNumber: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}account_number'],
      ),
      phoneNumber: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}phone_number'],
      ),
      smsSelected: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}sms_selected'],
      )!,
      whatsappSelected: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}whatsapp_selected'],
      )!,
      emailAddress: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}email_address'],
      ),
      emailSelected: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}email_selected'],
      )!,
      updatedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}updated_at'],
      )!,
    );
  }

  @override
  $LocalDraftTable createAlias(String alias) {
    return $LocalDraftTable(attachedDatabase, alias);
  }
}

class LocalDraftData extends DataClass implements Insertable<LocalDraftData> {
  final int id;
  final String? branchCode;
  final String? accountNumber;
  final String? phoneNumber;
  final bool smsSelected;
  final bool whatsappSelected;
  final String? emailAddress;

  /// customer.md Stage 1b: "The email channel row activates once an address is entered, **and is
  /// deselectable once active**." Meaningful only while an address is present; a customer can
  /// still deselect and later reselect without retyping the address. Added under review, S5-02.
  final bool emailSelected;
  final DateTime updatedAt;
  const LocalDraftData({
    required this.id,
    this.branchCode,
    this.accountNumber,
    this.phoneNumber,
    required this.smsSelected,
    required this.whatsappSelected,
    this.emailAddress,
    required this.emailSelected,
    required this.updatedAt,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<int>(id);
    if (!nullToAbsent || branchCode != null) {
      map['branch_code'] = Variable<String>(branchCode);
    }
    if (!nullToAbsent || accountNumber != null) {
      map['account_number'] = Variable<String>(accountNumber);
    }
    if (!nullToAbsent || phoneNumber != null) {
      map['phone_number'] = Variable<String>(phoneNumber);
    }
    map['sms_selected'] = Variable<bool>(smsSelected);
    map['whatsapp_selected'] = Variable<bool>(whatsappSelected);
    if (!nullToAbsent || emailAddress != null) {
      map['email_address'] = Variable<String>(emailAddress);
    }
    map['email_selected'] = Variable<bool>(emailSelected);
    map['updated_at'] = Variable<DateTime>(updatedAt);
    return map;
  }

  LocalDraftCompanion toCompanion(bool nullToAbsent) {
    return LocalDraftCompanion(
      id: Value(id),
      branchCode: branchCode == null && nullToAbsent
          ? const Value.absent()
          : Value(branchCode),
      accountNumber: accountNumber == null && nullToAbsent
          ? const Value.absent()
          : Value(accountNumber),
      phoneNumber: phoneNumber == null && nullToAbsent
          ? const Value.absent()
          : Value(phoneNumber),
      smsSelected: Value(smsSelected),
      whatsappSelected: Value(whatsappSelected),
      emailAddress: emailAddress == null && nullToAbsent
          ? const Value.absent()
          : Value(emailAddress),
      emailSelected: Value(emailSelected),
      updatedAt: Value(updatedAt),
    );
  }

  factory LocalDraftData.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return LocalDraftData(
      id: serializer.fromJson<int>(json['id']),
      branchCode: serializer.fromJson<String?>(json['branchCode']),
      accountNumber: serializer.fromJson<String?>(json['accountNumber']),
      phoneNumber: serializer.fromJson<String?>(json['phoneNumber']),
      smsSelected: serializer.fromJson<bool>(json['smsSelected']),
      whatsappSelected: serializer.fromJson<bool>(json['whatsappSelected']),
      emailAddress: serializer.fromJson<String?>(json['emailAddress']),
      emailSelected: serializer.fromJson<bool>(json['emailSelected']),
      updatedAt: serializer.fromJson<DateTime>(json['updatedAt']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<int>(id),
      'branchCode': serializer.toJson<String?>(branchCode),
      'accountNumber': serializer.toJson<String?>(accountNumber),
      'phoneNumber': serializer.toJson<String?>(phoneNumber),
      'smsSelected': serializer.toJson<bool>(smsSelected),
      'whatsappSelected': serializer.toJson<bool>(whatsappSelected),
      'emailAddress': serializer.toJson<String?>(emailAddress),
      'emailSelected': serializer.toJson<bool>(emailSelected),
      'updatedAt': serializer.toJson<DateTime>(updatedAt),
    };
  }

  LocalDraftData copyWith({
    int? id,
    Value<String?> branchCode = const Value.absent(),
    Value<String?> accountNumber = const Value.absent(),
    Value<String?> phoneNumber = const Value.absent(),
    bool? smsSelected,
    bool? whatsappSelected,
    Value<String?> emailAddress = const Value.absent(),
    bool? emailSelected,
    DateTime? updatedAt,
  }) => LocalDraftData(
    id: id ?? this.id,
    branchCode: branchCode.present ? branchCode.value : this.branchCode,
    accountNumber: accountNumber.present
        ? accountNumber.value
        : this.accountNumber,
    phoneNumber: phoneNumber.present ? phoneNumber.value : this.phoneNumber,
    smsSelected: smsSelected ?? this.smsSelected,
    whatsappSelected: whatsappSelected ?? this.whatsappSelected,
    emailAddress: emailAddress.present ? emailAddress.value : this.emailAddress,
    emailSelected: emailSelected ?? this.emailSelected,
    updatedAt: updatedAt ?? this.updatedAt,
  );
  LocalDraftData copyWithCompanion(LocalDraftCompanion data) {
    return LocalDraftData(
      id: data.id.present ? data.id.value : this.id,
      branchCode: data.branchCode.present
          ? data.branchCode.value
          : this.branchCode,
      accountNumber: data.accountNumber.present
          ? data.accountNumber.value
          : this.accountNumber,
      phoneNumber: data.phoneNumber.present
          ? data.phoneNumber.value
          : this.phoneNumber,
      smsSelected: data.smsSelected.present
          ? data.smsSelected.value
          : this.smsSelected,
      whatsappSelected: data.whatsappSelected.present
          ? data.whatsappSelected.value
          : this.whatsappSelected,
      emailAddress: data.emailAddress.present
          ? data.emailAddress.value
          : this.emailAddress,
      emailSelected: data.emailSelected.present
          ? data.emailSelected.value
          : this.emailSelected,
      updatedAt: data.updatedAt.present ? data.updatedAt.value : this.updatedAt,
    );
  }

  @override
  String toString() {
    return (StringBuffer('LocalDraftData(')
          ..write('id: $id, ')
          ..write('branchCode: $branchCode, ')
          ..write('accountNumber: $accountNumber, ')
          ..write('phoneNumber: $phoneNumber, ')
          ..write('smsSelected: $smsSelected, ')
          ..write('whatsappSelected: $whatsappSelected, ')
          ..write('emailAddress: $emailAddress, ')
          ..write('emailSelected: $emailSelected, ')
          ..write('updatedAt: $updatedAt')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    id,
    branchCode,
    accountNumber,
    phoneNumber,
    smsSelected,
    whatsappSelected,
    emailAddress,
    emailSelected,
    updatedAt,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is LocalDraftData &&
          other.id == this.id &&
          other.branchCode == this.branchCode &&
          other.accountNumber == this.accountNumber &&
          other.phoneNumber == this.phoneNumber &&
          other.smsSelected == this.smsSelected &&
          other.whatsappSelected == this.whatsappSelected &&
          other.emailAddress == this.emailAddress &&
          other.emailSelected == this.emailSelected &&
          other.updatedAt == this.updatedAt);
}

class LocalDraftCompanion extends UpdateCompanion<LocalDraftData> {
  final Value<int> id;
  final Value<String?> branchCode;
  final Value<String?> accountNumber;
  final Value<String?> phoneNumber;
  final Value<bool> smsSelected;
  final Value<bool> whatsappSelected;
  final Value<String?> emailAddress;
  final Value<bool> emailSelected;
  final Value<DateTime> updatedAt;
  const LocalDraftCompanion({
    this.id = const Value.absent(),
    this.branchCode = const Value.absent(),
    this.accountNumber = const Value.absent(),
    this.phoneNumber = const Value.absent(),
    this.smsSelected = const Value.absent(),
    this.whatsappSelected = const Value.absent(),
    this.emailAddress = const Value.absent(),
    this.emailSelected = const Value.absent(),
    this.updatedAt = const Value.absent(),
  });
  LocalDraftCompanion.insert({
    this.id = const Value.absent(),
    this.branchCode = const Value.absent(),
    this.accountNumber = const Value.absent(),
    this.phoneNumber = const Value.absent(),
    this.smsSelected = const Value.absent(),
    this.whatsappSelected = const Value.absent(),
    this.emailAddress = const Value.absent(),
    this.emailSelected = const Value.absent(),
    required DateTime updatedAt,
  }) : updatedAt = Value(updatedAt);
  static Insertable<LocalDraftData> custom({
    Expression<int>? id,
    Expression<String>? branchCode,
    Expression<String>? accountNumber,
    Expression<String>? phoneNumber,
    Expression<bool>? smsSelected,
    Expression<bool>? whatsappSelected,
    Expression<String>? emailAddress,
    Expression<bool>? emailSelected,
    Expression<DateTime>? updatedAt,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (branchCode != null) 'branch_code': branchCode,
      if (accountNumber != null) 'account_number': accountNumber,
      if (phoneNumber != null) 'phone_number': phoneNumber,
      if (smsSelected != null) 'sms_selected': smsSelected,
      if (whatsappSelected != null) 'whatsapp_selected': whatsappSelected,
      if (emailAddress != null) 'email_address': emailAddress,
      if (emailSelected != null) 'email_selected': emailSelected,
      if (updatedAt != null) 'updated_at': updatedAt,
    });
  }

  LocalDraftCompanion copyWith({
    Value<int>? id,
    Value<String?>? branchCode,
    Value<String?>? accountNumber,
    Value<String?>? phoneNumber,
    Value<bool>? smsSelected,
    Value<bool>? whatsappSelected,
    Value<String?>? emailAddress,
    Value<bool>? emailSelected,
    Value<DateTime>? updatedAt,
  }) {
    return LocalDraftCompanion(
      id: id ?? this.id,
      branchCode: branchCode ?? this.branchCode,
      accountNumber: accountNumber ?? this.accountNumber,
      phoneNumber: phoneNumber ?? this.phoneNumber,
      smsSelected: smsSelected ?? this.smsSelected,
      whatsappSelected: whatsappSelected ?? this.whatsappSelected,
      emailAddress: emailAddress ?? this.emailAddress,
      emailSelected: emailSelected ?? this.emailSelected,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<int>(id.value);
    }
    if (branchCode.present) {
      map['branch_code'] = Variable<String>(branchCode.value);
    }
    if (accountNumber.present) {
      map['account_number'] = Variable<String>(accountNumber.value);
    }
    if (phoneNumber.present) {
      map['phone_number'] = Variable<String>(phoneNumber.value);
    }
    if (smsSelected.present) {
      map['sms_selected'] = Variable<bool>(smsSelected.value);
    }
    if (whatsappSelected.present) {
      map['whatsapp_selected'] = Variable<bool>(whatsappSelected.value);
    }
    if (emailAddress.present) {
      map['email_address'] = Variable<String>(emailAddress.value);
    }
    if (emailSelected.present) {
      map['email_selected'] = Variable<bool>(emailSelected.value);
    }
    if (updatedAt.present) {
      map['updated_at'] = Variable<DateTime>(updatedAt.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('LocalDraftCompanion(')
          ..write('id: $id, ')
          ..write('branchCode: $branchCode, ')
          ..write('accountNumber: $accountNumber, ')
          ..write('phoneNumber: $phoneNumber, ')
          ..write('smsSelected: $smsSelected, ')
          ..write('whatsappSelected: $whatsappSelected, ')
          ..write('emailAddress: $emailAddress, ')
          ..write('emailSelected: $emailSelected, ')
          ..write('updatedAt: $updatedAt')
          ..write(')'))
        .toString();
  }
}

class $LocalProgressTable extends LocalProgress
    with TableInfo<$LocalProgressTable, LocalProgressData> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $LocalProgressTable(this.attachedDatabase, [this._alias]);
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
  static const VerificationMeta _verifiedBranchCodeMeta =
      const VerificationMeta('verifiedBranchCode');
  @override
  late final GeneratedColumn<String> verifiedBranchCode =
      GeneratedColumn<String>(
        'verified_branch_code',
        aliasedName,
        false,
        type: DriftSqlType.string,
        requiredDuringInsert: true,
      );
  static const VerificationMeta _verifiedAccountNumberMeta =
      const VerificationMeta('verifiedAccountNumber');
  @override
  late final GeneratedColumn<String> verifiedAccountNumber =
      GeneratedColumn<String>(
        'verified_account_number',
        aliasedName,
        false,
        type: DriftSqlType.string,
        requiredDuringInsert: true,
      );
  static const VerificationMeta _resumeStageMeta = const VerificationMeta(
    'resumeStage',
  );
  @override
  late final GeneratedColumn<String> resumeStage = GeneratedColumn<String>(
    'resume_stage',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _profileIdMeta = const VerificationMeta(
    'profileId',
  );
  @override
  late final GeneratedColumn<String> profileId = GeneratedColumn<String>(
    'profile_id',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _channelsSummaryMeta = const VerificationMeta(
    'channelsSummary',
  );
  @override
  late final GeneratedColumn<String> channelsSummary = GeneratedColumn<String>(
    'channels_summary',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _updatedAtMeta = const VerificationMeta(
    'updatedAt',
  );
  @override
  late final GeneratedColumn<DateTime> updatedAt = GeneratedColumn<DateTime>(
    'updated_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [
    id,
    verifiedBranchCode,
    verifiedAccountNumber,
    resumeStage,
    profileId,
    channelsSummary,
    updatedAt,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'local_progress';
  @override
  VerificationContext validateIntegrity(
    Insertable<LocalProgressData> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    }
    if (data.containsKey('verified_branch_code')) {
      context.handle(
        _verifiedBranchCodeMeta,
        verifiedBranchCode.isAcceptableOrUnknown(
          data['verified_branch_code']!,
          _verifiedBranchCodeMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_verifiedBranchCodeMeta);
    }
    if (data.containsKey('verified_account_number')) {
      context.handle(
        _verifiedAccountNumberMeta,
        verifiedAccountNumber.isAcceptableOrUnknown(
          data['verified_account_number']!,
          _verifiedAccountNumberMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_verifiedAccountNumberMeta);
    }
    if (data.containsKey('resume_stage')) {
      context.handle(
        _resumeStageMeta,
        resumeStage.isAcceptableOrUnknown(
          data['resume_stage']!,
          _resumeStageMeta,
        ),
      );
    } else if (isInserting) {
      context.missing(_resumeStageMeta);
    }
    if (data.containsKey('profile_id')) {
      context.handle(
        _profileIdMeta,
        profileId.isAcceptableOrUnknown(data['profile_id']!, _profileIdMeta),
      );
    }
    if (data.containsKey('channels_summary')) {
      context.handle(
        _channelsSummaryMeta,
        channelsSummary.isAcceptableOrUnknown(
          data['channels_summary']!,
          _channelsSummaryMeta,
        ),
      );
    }
    if (data.containsKey('updated_at')) {
      context.handle(
        _updatedAtMeta,
        updatedAt.isAcceptableOrUnknown(data['updated_at']!, _updatedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_updatedAtMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  LocalProgressData map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return LocalProgressData(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}id'],
      )!,
      verifiedBranchCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}verified_branch_code'],
      )!,
      verifiedAccountNumber: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}verified_account_number'],
      )!,
      resumeStage: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}resume_stage'],
      )!,
      profileId: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}profile_id'],
      ),
      channelsSummary: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}channels_summary'],
      ),
      updatedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}updated_at'],
      )!,
    );
  }

  @override
  $LocalProgressTable createAlias(String alias) {
    return $LocalProgressTable(attachedDatabase, alias);
  }
}

class LocalProgressData extends DataClass
    implements Insertable<LocalProgressData> {
  final int id;
  final String verifiedBranchCode;
  final String verifiedAccountNumber;

  /// `contactChannels` — 1a passed, not yet submitted 1b. `awaitingVerification` — 1b submitted,
  /// profile created, OTPs sent (Stage 2, which doesn't exist yet in this build).
  final String resumeStage;
  final String? profileId;

  /// A small display-only cache of the last `ContactChannelsResponse.channels` (channel/state/
  /// maskedDestination, pipe- and colon-joined) — NEVER treated as authoritative per Stage 13's
  /// ownership table (system-derived data is backend-owned); it exists solely so the resumed
  /// "awaiting verification" placeholder screen has something to render without a session-status
  /// endpoint to re-fetch it from.
  final String? channelsSummary;
  final DateTime updatedAt;
  const LocalProgressData({
    required this.id,
    required this.verifiedBranchCode,
    required this.verifiedAccountNumber,
    required this.resumeStage,
    this.profileId,
    this.channelsSummary,
    required this.updatedAt,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<int>(id);
    map['verified_branch_code'] = Variable<String>(verifiedBranchCode);
    map['verified_account_number'] = Variable<String>(verifiedAccountNumber);
    map['resume_stage'] = Variable<String>(resumeStage);
    if (!nullToAbsent || profileId != null) {
      map['profile_id'] = Variable<String>(profileId);
    }
    if (!nullToAbsent || channelsSummary != null) {
      map['channels_summary'] = Variable<String>(channelsSummary);
    }
    map['updated_at'] = Variable<DateTime>(updatedAt);
    return map;
  }

  LocalProgressCompanion toCompanion(bool nullToAbsent) {
    return LocalProgressCompanion(
      id: Value(id),
      verifiedBranchCode: Value(verifiedBranchCode),
      verifiedAccountNumber: Value(verifiedAccountNumber),
      resumeStage: Value(resumeStage),
      profileId: profileId == null && nullToAbsent
          ? const Value.absent()
          : Value(profileId),
      channelsSummary: channelsSummary == null && nullToAbsent
          ? const Value.absent()
          : Value(channelsSummary),
      updatedAt: Value(updatedAt),
    );
  }

  factory LocalProgressData.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return LocalProgressData(
      id: serializer.fromJson<int>(json['id']),
      verifiedBranchCode: serializer.fromJson<String>(
        json['verifiedBranchCode'],
      ),
      verifiedAccountNumber: serializer.fromJson<String>(
        json['verifiedAccountNumber'],
      ),
      resumeStage: serializer.fromJson<String>(json['resumeStage']),
      profileId: serializer.fromJson<String?>(json['profileId']),
      channelsSummary: serializer.fromJson<String?>(json['channelsSummary']),
      updatedAt: serializer.fromJson<DateTime>(json['updatedAt']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<int>(id),
      'verifiedBranchCode': serializer.toJson<String>(verifiedBranchCode),
      'verifiedAccountNumber': serializer.toJson<String>(verifiedAccountNumber),
      'resumeStage': serializer.toJson<String>(resumeStage),
      'profileId': serializer.toJson<String?>(profileId),
      'channelsSummary': serializer.toJson<String?>(channelsSummary),
      'updatedAt': serializer.toJson<DateTime>(updatedAt),
    };
  }

  LocalProgressData copyWith({
    int? id,
    String? verifiedBranchCode,
    String? verifiedAccountNumber,
    String? resumeStage,
    Value<String?> profileId = const Value.absent(),
    Value<String?> channelsSummary = const Value.absent(),
    DateTime? updatedAt,
  }) => LocalProgressData(
    id: id ?? this.id,
    verifiedBranchCode: verifiedBranchCode ?? this.verifiedBranchCode,
    verifiedAccountNumber: verifiedAccountNumber ?? this.verifiedAccountNumber,
    resumeStage: resumeStage ?? this.resumeStage,
    profileId: profileId.present ? profileId.value : this.profileId,
    channelsSummary: channelsSummary.present
        ? channelsSummary.value
        : this.channelsSummary,
    updatedAt: updatedAt ?? this.updatedAt,
  );
  LocalProgressData copyWithCompanion(LocalProgressCompanion data) {
    return LocalProgressData(
      id: data.id.present ? data.id.value : this.id,
      verifiedBranchCode: data.verifiedBranchCode.present
          ? data.verifiedBranchCode.value
          : this.verifiedBranchCode,
      verifiedAccountNumber: data.verifiedAccountNumber.present
          ? data.verifiedAccountNumber.value
          : this.verifiedAccountNumber,
      resumeStage: data.resumeStage.present
          ? data.resumeStage.value
          : this.resumeStage,
      profileId: data.profileId.present ? data.profileId.value : this.profileId,
      channelsSummary: data.channelsSummary.present
          ? data.channelsSummary.value
          : this.channelsSummary,
      updatedAt: data.updatedAt.present ? data.updatedAt.value : this.updatedAt,
    );
  }

  @override
  String toString() {
    return (StringBuffer('LocalProgressData(')
          ..write('id: $id, ')
          ..write('verifiedBranchCode: $verifiedBranchCode, ')
          ..write('verifiedAccountNumber: $verifiedAccountNumber, ')
          ..write('resumeStage: $resumeStage, ')
          ..write('profileId: $profileId, ')
          ..write('channelsSummary: $channelsSummary, ')
          ..write('updatedAt: $updatedAt')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(
    id,
    verifiedBranchCode,
    verifiedAccountNumber,
    resumeStage,
    profileId,
    channelsSummary,
    updatedAt,
  );
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is LocalProgressData &&
          other.id == this.id &&
          other.verifiedBranchCode == this.verifiedBranchCode &&
          other.verifiedAccountNumber == this.verifiedAccountNumber &&
          other.resumeStage == this.resumeStage &&
          other.profileId == this.profileId &&
          other.channelsSummary == this.channelsSummary &&
          other.updatedAt == this.updatedAt);
}

class LocalProgressCompanion extends UpdateCompanion<LocalProgressData> {
  final Value<int> id;
  final Value<String> verifiedBranchCode;
  final Value<String> verifiedAccountNumber;
  final Value<String> resumeStage;
  final Value<String?> profileId;
  final Value<String?> channelsSummary;
  final Value<DateTime> updatedAt;
  const LocalProgressCompanion({
    this.id = const Value.absent(),
    this.verifiedBranchCode = const Value.absent(),
    this.verifiedAccountNumber = const Value.absent(),
    this.resumeStage = const Value.absent(),
    this.profileId = const Value.absent(),
    this.channelsSummary = const Value.absent(),
    this.updatedAt = const Value.absent(),
  });
  LocalProgressCompanion.insert({
    this.id = const Value.absent(),
    required String verifiedBranchCode,
    required String verifiedAccountNumber,
    required String resumeStage,
    this.profileId = const Value.absent(),
    this.channelsSummary = const Value.absent(),
    required DateTime updatedAt,
  }) : verifiedBranchCode = Value(verifiedBranchCode),
       verifiedAccountNumber = Value(verifiedAccountNumber),
       resumeStage = Value(resumeStage),
       updatedAt = Value(updatedAt);
  static Insertable<LocalProgressData> custom({
    Expression<int>? id,
    Expression<String>? verifiedBranchCode,
    Expression<String>? verifiedAccountNumber,
    Expression<String>? resumeStage,
    Expression<String>? profileId,
    Expression<String>? channelsSummary,
    Expression<DateTime>? updatedAt,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (verifiedBranchCode != null)
        'verified_branch_code': verifiedBranchCode,
      if (verifiedAccountNumber != null)
        'verified_account_number': verifiedAccountNumber,
      if (resumeStage != null) 'resume_stage': resumeStage,
      if (profileId != null) 'profile_id': profileId,
      if (channelsSummary != null) 'channels_summary': channelsSummary,
      if (updatedAt != null) 'updated_at': updatedAt,
    });
  }

  LocalProgressCompanion copyWith({
    Value<int>? id,
    Value<String>? verifiedBranchCode,
    Value<String>? verifiedAccountNumber,
    Value<String>? resumeStage,
    Value<String?>? profileId,
    Value<String?>? channelsSummary,
    Value<DateTime>? updatedAt,
  }) {
    return LocalProgressCompanion(
      id: id ?? this.id,
      verifiedBranchCode: verifiedBranchCode ?? this.verifiedBranchCode,
      verifiedAccountNumber:
          verifiedAccountNumber ?? this.verifiedAccountNumber,
      resumeStage: resumeStage ?? this.resumeStage,
      profileId: profileId ?? this.profileId,
      channelsSummary: channelsSummary ?? this.channelsSummary,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<int>(id.value);
    }
    if (verifiedBranchCode.present) {
      map['verified_branch_code'] = Variable<String>(verifiedBranchCode.value);
    }
    if (verifiedAccountNumber.present) {
      map['verified_account_number'] = Variable<String>(
        verifiedAccountNumber.value,
      );
    }
    if (resumeStage.present) {
      map['resume_stage'] = Variable<String>(resumeStage.value);
    }
    if (profileId.present) {
      map['profile_id'] = Variable<String>(profileId.value);
    }
    if (channelsSummary.present) {
      map['channels_summary'] = Variable<String>(channelsSummary.value);
    }
    if (updatedAt.present) {
      map['updated_at'] = Variable<DateTime>(updatedAt.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('LocalProgressCompanion(')
          ..write('id: $id, ')
          ..write('verifiedBranchCode: $verifiedBranchCode, ')
          ..write('verifiedAccountNumber: $verifiedAccountNumber, ')
          ..write('resumeStage: $resumeStage, ')
          ..write('profileId: $profileId, ')
          ..write('channelsSummary: $channelsSummary, ')
          ..write('updatedAt: $updatedAt')
          ..write(')'))
        .toString();
  }
}

class $DataEntryDraftTable extends DataEntryDraft
    with TableInfo<$DataEntryDraftTable, DataEntryDraftData> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $DataEntryDraftTable(this.attachedDatabase, [this._alias]);
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
  static const VerificationMeta _sexDeclaredMeta = const VerificationMeta(
    'sexDeclared',
  );
  @override
  late final GeneratedColumn<String> sexDeclared = GeneratedColumn<String>(
    'sex_declared',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _ethnicityMeta = const VerificationMeta(
    'ethnicity',
  );
  @override
  late final GeneratedColumn<String> ethnicity = GeneratedColumn<String>(
    'ethnicity',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _countryOfResidenceCodeMeta =
      const VerificationMeta('countryOfResidenceCode');
  @override
  late final GeneratedColumn<String> countryOfResidenceCode =
      GeneratedColumn<String>(
        'country_of_residence_code',
        aliasedName,
        true,
        type: DriftSqlType.string,
        requiredDuringInsert: false,
      );
  static const VerificationMeta _maritalStatusMeta = const VerificationMeta(
    'maritalStatus',
  );
  @override
  late final GeneratedColumn<String> maritalStatus = GeneratedColumn<String>(
    'marital_status',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _spouseNameMeta = const VerificationMeta(
    'spouseName',
  );
  @override
  late final GeneratedColumn<String> spouseName = GeneratedColumn<String>(
    'spouse_name',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _hasChildrenMeta = const VerificationMeta(
    'hasChildren',
  );
  @override
  late final GeneratedColumn<bool> hasChildren = GeneratedColumn<bool>(
    'has_children',
    aliasedName,
    true,
    type: DriftSqlType.bool,
    requiredDuringInsert: false,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("has_children" IN (0, 1))',
    ),
  );
  static const VerificationMeta _childrenCountMeta = const VerificationMeta(
    'childrenCount',
  );
  @override
  late final GeneratedColumn<int> childrenCount = GeneratedColumn<int>(
    'children_count',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _educationLevelMeta = const VerificationMeta(
    'educationLevel',
  );
  @override
  late final GeneratedColumn<int> educationLevel = GeneratedColumn<int>(
    'education_level',
    aliasedName,
    true,
    type: DriftSqlType.int,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _birthCountryCodeMeta = const VerificationMeta(
    'birthCountryCode',
  );
  @override
  late final GeneratedColumn<String> birthCountryCode = GeneratedColumn<String>(
    'birth_country_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _birthStateCodeMeta = const VerificationMeta(
    'birthStateCode',
  );
  @override
  late final GeneratedColumn<String> birthStateCode = GeneratedColumn<String>(
    'birth_state_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _birthStateTextMeta = const VerificationMeta(
    'birthStateText',
  );
  @override
  late final GeneratedColumn<String> birthStateText = GeneratedColumn<String>(
    'birth_state_text',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _birthCityTextMeta = const VerificationMeta(
    'birthCityText',
  );
  @override
  late final GeneratedColumn<String> birthCityText = GeneratedColumn<String>(
    'birth_city_text',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _occupationCodeMeta = const VerificationMeta(
    'occupationCode',
  );
  @override
  late final GeneratedColumn<String> occupationCode = GeneratedColumn<String>(
    'occupation_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _monthlyExpensesSdgMeta =
      const VerificationMeta('monthlyExpensesSdg');
  @override
  late final GeneratedColumn<String> monthlyExpensesSdg =
      GeneratedColumn<String>(
        'monthly_expenses_sdg',
        aliasedName,
        true,
        type: DriftSqlType.string,
        requiredDuringInsert: false,
      );
  static const VerificationMeta _homeCountryCodeMeta = const VerificationMeta(
    'homeCountryCode',
  );
  @override
  late final GeneratedColumn<String> homeCountryCode = GeneratedColumn<String>(
    'home_country_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _homeStateCodeMeta = const VerificationMeta(
    'homeStateCode',
  );
  @override
  late final GeneratedColumn<String> homeStateCode = GeneratedColumn<String>(
    'home_state_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _homeStateTextMeta = const VerificationMeta(
    'homeStateText',
  );
  @override
  late final GeneratedColumn<String> homeStateText = GeneratedColumn<String>(
    'home_state_text',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _homeLocalityCodeMeta = const VerificationMeta(
    'homeLocalityCode',
  );
  @override
  late final GeneratedColumn<String> homeLocalityCode = GeneratedColumn<String>(
    'home_locality_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _homeLocalityTextMeta = const VerificationMeta(
    'homeLocalityText',
  );
  @override
  late final GeneratedColumn<String> homeLocalityText = GeneratedColumn<String>(
    'home_locality_text',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _homeCityMeta = const VerificationMeta(
    'homeCity',
  );
  @override
  late final GeneratedColumn<String> homeCity = GeneratedColumn<String>(
    'home_city',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _homeAreaMeta = const VerificationMeta(
    'homeArea',
  );
  @override
  late final GeneratedColumn<String> homeArea = GeneratedColumn<String>(
    'home_area',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _homeStreetMeta = const VerificationMeta(
    'homeStreet',
  );
  @override
  late final GeneratedColumn<String> homeStreet = GeneratedColumn<String>(
    'home_street',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _homeBlockMeta = const VerificationMeta(
    'homeBlock',
  );
  @override
  late final GeneratedColumn<String> homeBlock = GeneratedColumn<String>(
    'home_block',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _homeHouseNumberMeta = const VerificationMeta(
    'homeHouseNumber',
  );
  @override
  late final GeneratedColumn<String> homeHouseNumber = GeneratedColumn<String>(
    'home_house_number',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workEmployerMeta = const VerificationMeta(
    'workEmployer',
  );
  @override
  late final GeneratedColumn<String> workEmployer = GeneratedColumn<String>(
    'work_employer',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workCountryCodeMeta = const VerificationMeta(
    'workCountryCode',
  );
  @override
  late final GeneratedColumn<String> workCountryCode = GeneratedColumn<String>(
    'work_country_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workStateCodeMeta = const VerificationMeta(
    'workStateCode',
  );
  @override
  late final GeneratedColumn<String> workStateCode = GeneratedColumn<String>(
    'work_state_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workStateTextMeta = const VerificationMeta(
    'workStateText',
  );
  @override
  late final GeneratedColumn<String> workStateText = GeneratedColumn<String>(
    'work_state_text',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workLocalityCodeMeta = const VerificationMeta(
    'workLocalityCode',
  );
  @override
  late final GeneratedColumn<String> workLocalityCode = GeneratedColumn<String>(
    'work_locality_code',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workLocalityTextMeta = const VerificationMeta(
    'workLocalityText',
  );
  @override
  late final GeneratedColumn<String> workLocalityText = GeneratedColumn<String>(
    'work_locality_text',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workCityMeta = const VerificationMeta(
    'workCity',
  );
  @override
  late final GeneratedColumn<String> workCity = GeneratedColumn<String>(
    'work_city',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workAreaMeta = const VerificationMeta(
    'workArea',
  );
  @override
  late final GeneratedColumn<String> workArea = GeneratedColumn<String>(
    'work_area',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workStreetMeta = const VerificationMeta(
    'workStreet',
  );
  @override
  late final GeneratedColumn<String> workStreet = GeneratedColumn<String>(
    'work_street',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _workBlockMeta = const VerificationMeta(
    'workBlock',
  );
  @override
  late final GeneratedColumn<String> workBlock = GeneratedColumn<String>(
    'work_block',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _salaryCertificatePathMeta =
      const VerificationMeta('salaryCertificatePath');
  @override
  late final GeneratedColumn<String> salaryCertificatePath =
      GeneratedColumn<String>(
        'salary_certificate_path',
        aliasedName,
        true,
        type: DriftSqlType.string,
        requiredDuringInsert: false,
      );
  static const VerificationMeta _salaryCertificateUploadedAtMeta =
      const VerificationMeta('salaryCertificateUploadedAt');
  @override
  late final GeneratedColumn<DateTime> salaryCertificateUploadedAt =
      GeneratedColumn<DateTime>(
        'salary_certificate_uploaded_at',
        aliasedName,
        true,
        type: DriftSqlType.dateTime,
        requiredDuringInsert: false,
      );
  static const VerificationMeta _identityTypeMeta = const VerificationMeta(
    'identityType',
  );
  @override
  late final GeneratedColumn<String> identityType = GeneratedColumn<String>(
    'identity_type',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  static const VerificationMeta _updatedAtMeta = const VerificationMeta(
    'updatedAt',
  );
  @override
  late final GeneratedColumn<DateTime> updatedAt = GeneratedColumn<DateTime>(
    'updated_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [
    id,
    sexDeclared,
    ethnicity,
    countryOfResidenceCode,
    maritalStatus,
    spouseName,
    hasChildren,
    childrenCount,
    educationLevel,
    birthCountryCode,
    birthStateCode,
    birthStateText,
    birthCityText,
    occupationCode,
    monthlyExpensesSdg,
    homeCountryCode,
    homeStateCode,
    homeStateText,
    homeLocalityCode,
    homeLocalityText,
    homeCity,
    homeArea,
    homeStreet,
    homeBlock,
    homeHouseNumber,
    workEmployer,
    workCountryCode,
    workStateCode,
    workStateText,
    workLocalityCode,
    workLocalityText,
    workCity,
    workArea,
    workStreet,
    workBlock,
    salaryCertificatePath,
    salaryCertificateUploadedAt,
    identityType,
    updatedAt,
  ];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'data_entry_draft';
  @override
  VerificationContext validateIntegrity(
    Insertable<DataEntryDraftData> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('id')) {
      context.handle(_idMeta, id.isAcceptableOrUnknown(data['id']!, _idMeta));
    }
    if (data.containsKey('sex_declared')) {
      context.handle(
        _sexDeclaredMeta,
        sexDeclared.isAcceptableOrUnknown(
          data['sex_declared']!,
          _sexDeclaredMeta,
        ),
      );
    }
    if (data.containsKey('ethnicity')) {
      context.handle(
        _ethnicityMeta,
        ethnicity.isAcceptableOrUnknown(data['ethnicity']!, _ethnicityMeta),
      );
    }
    if (data.containsKey('country_of_residence_code')) {
      context.handle(
        _countryOfResidenceCodeMeta,
        countryOfResidenceCode.isAcceptableOrUnknown(
          data['country_of_residence_code']!,
          _countryOfResidenceCodeMeta,
        ),
      );
    }
    if (data.containsKey('marital_status')) {
      context.handle(
        _maritalStatusMeta,
        maritalStatus.isAcceptableOrUnknown(
          data['marital_status']!,
          _maritalStatusMeta,
        ),
      );
    }
    if (data.containsKey('spouse_name')) {
      context.handle(
        _spouseNameMeta,
        spouseName.isAcceptableOrUnknown(data['spouse_name']!, _spouseNameMeta),
      );
    }
    if (data.containsKey('has_children')) {
      context.handle(
        _hasChildrenMeta,
        hasChildren.isAcceptableOrUnknown(
          data['has_children']!,
          _hasChildrenMeta,
        ),
      );
    }
    if (data.containsKey('children_count')) {
      context.handle(
        _childrenCountMeta,
        childrenCount.isAcceptableOrUnknown(
          data['children_count']!,
          _childrenCountMeta,
        ),
      );
    }
    if (data.containsKey('education_level')) {
      context.handle(
        _educationLevelMeta,
        educationLevel.isAcceptableOrUnknown(
          data['education_level']!,
          _educationLevelMeta,
        ),
      );
    }
    if (data.containsKey('birth_country_code')) {
      context.handle(
        _birthCountryCodeMeta,
        birthCountryCode.isAcceptableOrUnknown(
          data['birth_country_code']!,
          _birthCountryCodeMeta,
        ),
      );
    }
    if (data.containsKey('birth_state_code')) {
      context.handle(
        _birthStateCodeMeta,
        birthStateCode.isAcceptableOrUnknown(
          data['birth_state_code']!,
          _birthStateCodeMeta,
        ),
      );
    }
    if (data.containsKey('birth_state_text')) {
      context.handle(
        _birthStateTextMeta,
        birthStateText.isAcceptableOrUnknown(
          data['birth_state_text']!,
          _birthStateTextMeta,
        ),
      );
    }
    if (data.containsKey('birth_city_text')) {
      context.handle(
        _birthCityTextMeta,
        birthCityText.isAcceptableOrUnknown(
          data['birth_city_text']!,
          _birthCityTextMeta,
        ),
      );
    }
    if (data.containsKey('occupation_code')) {
      context.handle(
        _occupationCodeMeta,
        occupationCode.isAcceptableOrUnknown(
          data['occupation_code']!,
          _occupationCodeMeta,
        ),
      );
    }
    if (data.containsKey('monthly_expenses_sdg')) {
      context.handle(
        _monthlyExpensesSdgMeta,
        monthlyExpensesSdg.isAcceptableOrUnknown(
          data['monthly_expenses_sdg']!,
          _monthlyExpensesSdgMeta,
        ),
      );
    }
    if (data.containsKey('home_country_code')) {
      context.handle(
        _homeCountryCodeMeta,
        homeCountryCode.isAcceptableOrUnknown(
          data['home_country_code']!,
          _homeCountryCodeMeta,
        ),
      );
    }
    if (data.containsKey('home_state_code')) {
      context.handle(
        _homeStateCodeMeta,
        homeStateCode.isAcceptableOrUnknown(
          data['home_state_code']!,
          _homeStateCodeMeta,
        ),
      );
    }
    if (data.containsKey('home_state_text')) {
      context.handle(
        _homeStateTextMeta,
        homeStateText.isAcceptableOrUnknown(
          data['home_state_text']!,
          _homeStateTextMeta,
        ),
      );
    }
    if (data.containsKey('home_locality_code')) {
      context.handle(
        _homeLocalityCodeMeta,
        homeLocalityCode.isAcceptableOrUnknown(
          data['home_locality_code']!,
          _homeLocalityCodeMeta,
        ),
      );
    }
    if (data.containsKey('home_locality_text')) {
      context.handle(
        _homeLocalityTextMeta,
        homeLocalityText.isAcceptableOrUnknown(
          data['home_locality_text']!,
          _homeLocalityTextMeta,
        ),
      );
    }
    if (data.containsKey('home_city')) {
      context.handle(
        _homeCityMeta,
        homeCity.isAcceptableOrUnknown(data['home_city']!, _homeCityMeta),
      );
    }
    if (data.containsKey('home_area')) {
      context.handle(
        _homeAreaMeta,
        homeArea.isAcceptableOrUnknown(data['home_area']!, _homeAreaMeta),
      );
    }
    if (data.containsKey('home_street')) {
      context.handle(
        _homeStreetMeta,
        homeStreet.isAcceptableOrUnknown(data['home_street']!, _homeStreetMeta),
      );
    }
    if (data.containsKey('home_block')) {
      context.handle(
        _homeBlockMeta,
        homeBlock.isAcceptableOrUnknown(data['home_block']!, _homeBlockMeta),
      );
    }
    if (data.containsKey('home_house_number')) {
      context.handle(
        _homeHouseNumberMeta,
        homeHouseNumber.isAcceptableOrUnknown(
          data['home_house_number']!,
          _homeHouseNumberMeta,
        ),
      );
    }
    if (data.containsKey('work_employer')) {
      context.handle(
        _workEmployerMeta,
        workEmployer.isAcceptableOrUnknown(
          data['work_employer']!,
          _workEmployerMeta,
        ),
      );
    }
    if (data.containsKey('work_country_code')) {
      context.handle(
        _workCountryCodeMeta,
        workCountryCode.isAcceptableOrUnknown(
          data['work_country_code']!,
          _workCountryCodeMeta,
        ),
      );
    }
    if (data.containsKey('work_state_code')) {
      context.handle(
        _workStateCodeMeta,
        workStateCode.isAcceptableOrUnknown(
          data['work_state_code']!,
          _workStateCodeMeta,
        ),
      );
    }
    if (data.containsKey('work_state_text')) {
      context.handle(
        _workStateTextMeta,
        workStateText.isAcceptableOrUnknown(
          data['work_state_text']!,
          _workStateTextMeta,
        ),
      );
    }
    if (data.containsKey('work_locality_code')) {
      context.handle(
        _workLocalityCodeMeta,
        workLocalityCode.isAcceptableOrUnknown(
          data['work_locality_code']!,
          _workLocalityCodeMeta,
        ),
      );
    }
    if (data.containsKey('work_locality_text')) {
      context.handle(
        _workLocalityTextMeta,
        workLocalityText.isAcceptableOrUnknown(
          data['work_locality_text']!,
          _workLocalityTextMeta,
        ),
      );
    }
    if (data.containsKey('work_city')) {
      context.handle(
        _workCityMeta,
        workCity.isAcceptableOrUnknown(data['work_city']!, _workCityMeta),
      );
    }
    if (data.containsKey('work_area')) {
      context.handle(
        _workAreaMeta,
        workArea.isAcceptableOrUnknown(data['work_area']!, _workAreaMeta),
      );
    }
    if (data.containsKey('work_street')) {
      context.handle(
        _workStreetMeta,
        workStreet.isAcceptableOrUnknown(data['work_street']!, _workStreetMeta),
      );
    }
    if (data.containsKey('work_block')) {
      context.handle(
        _workBlockMeta,
        workBlock.isAcceptableOrUnknown(data['work_block']!, _workBlockMeta),
      );
    }
    if (data.containsKey('salary_certificate_path')) {
      context.handle(
        _salaryCertificatePathMeta,
        salaryCertificatePath.isAcceptableOrUnknown(
          data['salary_certificate_path']!,
          _salaryCertificatePathMeta,
        ),
      );
    }
    if (data.containsKey('salary_certificate_uploaded_at')) {
      context.handle(
        _salaryCertificateUploadedAtMeta,
        salaryCertificateUploadedAt.isAcceptableOrUnknown(
          data['salary_certificate_uploaded_at']!,
          _salaryCertificateUploadedAtMeta,
        ),
      );
    }
    if (data.containsKey('identity_type')) {
      context.handle(
        _identityTypeMeta,
        identityType.isAcceptableOrUnknown(
          data['identity_type']!,
          _identityTypeMeta,
        ),
      );
    }
    if (data.containsKey('updated_at')) {
      context.handle(
        _updatedAtMeta,
        updatedAt.isAcceptableOrUnknown(data['updated_at']!, _updatedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_updatedAtMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {id};
  @override
  DataEntryDraftData map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return DataEntryDraftData(
      id: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}id'],
      )!,
      sexDeclared: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}sex_declared'],
      ),
      ethnicity: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}ethnicity'],
      ),
      countryOfResidenceCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}country_of_residence_code'],
      ),
      maritalStatus: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}marital_status'],
      ),
      spouseName: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}spouse_name'],
      ),
      hasChildren: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}has_children'],
      ),
      childrenCount: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}children_count'],
      ),
      educationLevel: attachedDatabase.typeMapping.read(
        DriftSqlType.int,
        data['${effectivePrefix}education_level'],
      ),
      birthCountryCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}birth_country_code'],
      ),
      birthStateCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}birth_state_code'],
      ),
      birthStateText: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}birth_state_text'],
      ),
      birthCityText: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}birth_city_text'],
      ),
      occupationCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}occupation_code'],
      ),
      monthlyExpensesSdg: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}monthly_expenses_sdg'],
      ),
      homeCountryCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_country_code'],
      ),
      homeStateCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_state_code'],
      ),
      homeStateText: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_state_text'],
      ),
      homeLocalityCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_locality_code'],
      ),
      homeLocalityText: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_locality_text'],
      ),
      homeCity: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_city'],
      ),
      homeArea: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_area'],
      ),
      homeStreet: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_street'],
      ),
      homeBlock: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_block'],
      ),
      homeHouseNumber: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}home_house_number'],
      ),
      workEmployer: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_employer'],
      ),
      workCountryCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_country_code'],
      ),
      workStateCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_state_code'],
      ),
      workStateText: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_state_text'],
      ),
      workLocalityCode: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_locality_code'],
      ),
      workLocalityText: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_locality_text'],
      ),
      workCity: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_city'],
      ),
      workArea: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_area'],
      ),
      workStreet: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_street'],
      ),
      workBlock: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}work_block'],
      ),
      salaryCertificatePath: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}salary_certificate_path'],
      ),
      salaryCertificateUploadedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}salary_certificate_uploaded_at'],
      ),
      identityType: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}identity_type'],
      ),
      updatedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}updated_at'],
      )!,
    );
  }

  @override
  $DataEntryDraftTable createAlias(String alias) {
    return $DataEntryDraftTable(attachedDatabase, alias);
  }
}

class DataEntryDraftData extends DataClass
    implements Insertable<DataEntryDraftData> {
  final int id;
  final String? sexDeclared;
  final String? ethnicity;
  final String? countryOfResidenceCode;
  final String? maritalStatus;
  final String? spouseName;
  final bool? hasChildren;
  final int? childrenCount;
  final int? educationLevel;
  final String? birthCountryCode;
  final String? birthStateCode;
  final String? birthStateText;
  final String? birthCityText;
  final String? occupationCode;
  final String? monthlyExpensesSdg;
  final String? homeCountryCode;
  final String? homeStateCode;
  final String? homeStateText;
  final String? homeLocalityCode;
  final String? homeLocalityText;
  final String? homeCity;
  final String? homeArea;
  final String? homeStreet;
  final String? homeBlock;
  final String? homeHouseNumber;
  final String? workEmployer;
  final String? workCountryCode;
  final String? workStateCode;
  final String? workStateText;
  final String? workLocalityCode;
  final String? workLocalityText;
  final String? workCity;
  final String? workArea;
  final String? workStreet;
  final String? workBlock;
  final String? salaryCertificatePath;

  /// When the file at [salaryCertificatePath] was accepted by
  /// `POST /api/v1/salary-certificate`, or null if it has not been (BL-105, S8-14).
  ///
  /// **The whole point of this column is that «تم إرفاق» must not be a guess.** A path alone only
  /// proves a file is on this handset; it says nothing about whether the bank has it, and before
  /// S8-14 the screen showed the confirmation on the strength of the path. The certificate gates
  /// nothing (customer.md Stage 6), so a failed upload must never block Next — it must simply
  /// stop the app claiming an attachment that does not exist.
  final DateTime? salaryCertificateUploadedAt;
  final String? identityType;
  final DateTime updatedAt;
  const DataEntryDraftData({
    required this.id,
    this.sexDeclared,
    this.ethnicity,
    this.countryOfResidenceCode,
    this.maritalStatus,
    this.spouseName,
    this.hasChildren,
    this.childrenCount,
    this.educationLevel,
    this.birthCountryCode,
    this.birthStateCode,
    this.birthStateText,
    this.birthCityText,
    this.occupationCode,
    this.monthlyExpensesSdg,
    this.homeCountryCode,
    this.homeStateCode,
    this.homeStateText,
    this.homeLocalityCode,
    this.homeLocalityText,
    this.homeCity,
    this.homeArea,
    this.homeStreet,
    this.homeBlock,
    this.homeHouseNumber,
    this.workEmployer,
    this.workCountryCode,
    this.workStateCode,
    this.workStateText,
    this.workLocalityCode,
    this.workLocalityText,
    this.workCity,
    this.workArea,
    this.workStreet,
    this.workBlock,
    this.salaryCertificatePath,
    this.salaryCertificateUploadedAt,
    this.identityType,
    required this.updatedAt,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['id'] = Variable<int>(id);
    if (!nullToAbsent || sexDeclared != null) {
      map['sex_declared'] = Variable<String>(sexDeclared);
    }
    if (!nullToAbsent || ethnicity != null) {
      map['ethnicity'] = Variable<String>(ethnicity);
    }
    if (!nullToAbsent || countryOfResidenceCode != null) {
      map['country_of_residence_code'] = Variable<String>(
        countryOfResidenceCode,
      );
    }
    if (!nullToAbsent || maritalStatus != null) {
      map['marital_status'] = Variable<String>(maritalStatus);
    }
    if (!nullToAbsent || spouseName != null) {
      map['spouse_name'] = Variable<String>(spouseName);
    }
    if (!nullToAbsent || hasChildren != null) {
      map['has_children'] = Variable<bool>(hasChildren);
    }
    if (!nullToAbsent || childrenCount != null) {
      map['children_count'] = Variable<int>(childrenCount);
    }
    if (!nullToAbsent || educationLevel != null) {
      map['education_level'] = Variable<int>(educationLevel);
    }
    if (!nullToAbsent || birthCountryCode != null) {
      map['birth_country_code'] = Variable<String>(birthCountryCode);
    }
    if (!nullToAbsent || birthStateCode != null) {
      map['birth_state_code'] = Variable<String>(birthStateCode);
    }
    if (!nullToAbsent || birthStateText != null) {
      map['birth_state_text'] = Variable<String>(birthStateText);
    }
    if (!nullToAbsent || birthCityText != null) {
      map['birth_city_text'] = Variable<String>(birthCityText);
    }
    if (!nullToAbsent || occupationCode != null) {
      map['occupation_code'] = Variable<String>(occupationCode);
    }
    if (!nullToAbsent || monthlyExpensesSdg != null) {
      map['monthly_expenses_sdg'] = Variable<String>(monthlyExpensesSdg);
    }
    if (!nullToAbsent || homeCountryCode != null) {
      map['home_country_code'] = Variable<String>(homeCountryCode);
    }
    if (!nullToAbsent || homeStateCode != null) {
      map['home_state_code'] = Variable<String>(homeStateCode);
    }
    if (!nullToAbsent || homeStateText != null) {
      map['home_state_text'] = Variable<String>(homeStateText);
    }
    if (!nullToAbsent || homeLocalityCode != null) {
      map['home_locality_code'] = Variable<String>(homeLocalityCode);
    }
    if (!nullToAbsent || homeLocalityText != null) {
      map['home_locality_text'] = Variable<String>(homeLocalityText);
    }
    if (!nullToAbsent || homeCity != null) {
      map['home_city'] = Variable<String>(homeCity);
    }
    if (!nullToAbsent || homeArea != null) {
      map['home_area'] = Variable<String>(homeArea);
    }
    if (!nullToAbsent || homeStreet != null) {
      map['home_street'] = Variable<String>(homeStreet);
    }
    if (!nullToAbsent || homeBlock != null) {
      map['home_block'] = Variable<String>(homeBlock);
    }
    if (!nullToAbsent || homeHouseNumber != null) {
      map['home_house_number'] = Variable<String>(homeHouseNumber);
    }
    if (!nullToAbsent || workEmployer != null) {
      map['work_employer'] = Variable<String>(workEmployer);
    }
    if (!nullToAbsent || workCountryCode != null) {
      map['work_country_code'] = Variable<String>(workCountryCode);
    }
    if (!nullToAbsent || workStateCode != null) {
      map['work_state_code'] = Variable<String>(workStateCode);
    }
    if (!nullToAbsent || workStateText != null) {
      map['work_state_text'] = Variable<String>(workStateText);
    }
    if (!nullToAbsent || workLocalityCode != null) {
      map['work_locality_code'] = Variable<String>(workLocalityCode);
    }
    if (!nullToAbsent || workLocalityText != null) {
      map['work_locality_text'] = Variable<String>(workLocalityText);
    }
    if (!nullToAbsent || workCity != null) {
      map['work_city'] = Variable<String>(workCity);
    }
    if (!nullToAbsent || workArea != null) {
      map['work_area'] = Variable<String>(workArea);
    }
    if (!nullToAbsent || workStreet != null) {
      map['work_street'] = Variable<String>(workStreet);
    }
    if (!nullToAbsent || workBlock != null) {
      map['work_block'] = Variable<String>(workBlock);
    }
    if (!nullToAbsent || salaryCertificatePath != null) {
      map['salary_certificate_path'] = Variable<String>(salaryCertificatePath);
    }
    if (!nullToAbsent || salaryCertificateUploadedAt != null) {
      map['salary_certificate_uploaded_at'] = Variable<DateTime>(
        salaryCertificateUploadedAt,
      );
    }
    if (!nullToAbsent || identityType != null) {
      map['identity_type'] = Variable<String>(identityType);
    }
    map['updated_at'] = Variable<DateTime>(updatedAt);
    return map;
  }

  DataEntryDraftCompanion toCompanion(bool nullToAbsent) {
    return DataEntryDraftCompanion(
      id: Value(id),
      sexDeclared: sexDeclared == null && nullToAbsent
          ? const Value.absent()
          : Value(sexDeclared),
      ethnicity: ethnicity == null && nullToAbsent
          ? const Value.absent()
          : Value(ethnicity),
      countryOfResidenceCode: countryOfResidenceCode == null && nullToAbsent
          ? const Value.absent()
          : Value(countryOfResidenceCode),
      maritalStatus: maritalStatus == null && nullToAbsent
          ? const Value.absent()
          : Value(maritalStatus),
      spouseName: spouseName == null && nullToAbsent
          ? const Value.absent()
          : Value(spouseName),
      hasChildren: hasChildren == null && nullToAbsent
          ? const Value.absent()
          : Value(hasChildren),
      childrenCount: childrenCount == null && nullToAbsent
          ? const Value.absent()
          : Value(childrenCount),
      educationLevel: educationLevel == null && nullToAbsent
          ? const Value.absent()
          : Value(educationLevel),
      birthCountryCode: birthCountryCode == null && nullToAbsent
          ? const Value.absent()
          : Value(birthCountryCode),
      birthStateCode: birthStateCode == null && nullToAbsent
          ? const Value.absent()
          : Value(birthStateCode),
      birthStateText: birthStateText == null && nullToAbsent
          ? const Value.absent()
          : Value(birthStateText),
      birthCityText: birthCityText == null && nullToAbsent
          ? const Value.absent()
          : Value(birthCityText),
      occupationCode: occupationCode == null && nullToAbsent
          ? const Value.absent()
          : Value(occupationCode),
      monthlyExpensesSdg: monthlyExpensesSdg == null && nullToAbsent
          ? const Value.absent()
          : Value(monthlyExpensesSdg),
      homeCountryCode: homeCountryCode == null && nullToAbsent
          ? const Value.absent()
          : Value(homeCountryCode),
      homeStateCode: homeStateCode == null && nullToAbsent
          ? const Value.absent()
          : Value(homeStateCode),
      homeStateText: homeStateText == null && nullToAbsent
          ? const Value.absent()
          : Value(homeStateText),
      homeLocalityCode: homeLocalityCode == null && nullToAbsent
          ? const Value.absent()
          : Value(homeLocalityCode),
      homeLocalityText: homeLocalityText == null && nullToAbsent
          ? const Value.absent()
          : Value(homeLocalityText),
      homeCity: homeCity == null && nullToAbsent
          ? const Value.absent()
          : Value(homeCity),
      homeArea: homeArea == null && nullToAbsent
          ? const Value.absent()
          : Value(homeArea),
      homeStreet: homeStreet == null && nullToAbsent
          ? const Value.absent()
          : Value(homeStreet),
      homeBlock: homeBlock == null && nullToAbsent
          ? const Value.absent()
          : Value(homeBlock),
      homeHouseNumber: homeHouseNumber == null && nullToAbsent
          ? const Value.absent()
          : Value(homeHouseNumber),
      workEmployer: workEmployer == null && nullToAbsent
          ? const Value.absent()
          : Value(workEmployer),
      workCountryCode: workCountryCode == null && nullToAbsent
          ? const Value.absent()
          : Value(workCountryCode),
      workStateCode: workStateCode == null && nullToAbsent
          ? const Value.absent()
          : Value(workStateCode),
      workStateText: workStateText == null && nullToAbsent
          ? const Value.absent()
          : Value(workStateText),
      workLocalityCode: workLocalityCode == null && nullToAbsent
          ? const Value.absent()
          : Value(workLocalityCode),
      workLocalityText: workLocalityText == null && nullToAbsent
          ? const Value.absent()
          : Value(workLocalityText),
      workCity: workCity == null && nullToAbsent
          ? const Value.absent()
          : Value(workCity),
      workArea: workArea == null && nullToAbsent
          ? const Value.absent()
          : Value(workArea),
      workStreet: workStreet == null && nullToAbsent
          ? const Value.absent()
          : Value(workStreet),
      workBlock: workBlock == null && nullToAbsent
          ? const Value.absent()
          : Value(workBlock),
      salaryCertificatePath: salaryCertificatePath == null && nullToAbsent
          ? const Value.absent()
          : Value(salaryCertificatePath),
      salaryCertificateUploadedAt:
          salaryCertificateUploadedAt == null && nullToAbsent
          ? const Value.absent()
          : Value(salaryCertificateUploadedAt),
      identityType: identityType == null && nullToAbsent
          ? const Value.absent()
          : Value(identityType),
      updatedAt: Value(updatedAt),
    );
  }

  factory DataEntryDraftData.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return DataEntryDraftData(
      id: serializer.fromJson<int>(json['id']),
      sexDeclared: serializer.fromJson<String?>(json['sexDeclared']),
      ethnicity: serializer.fromJson<String?>(json['ethnicity']),
      countryOfResidenceCode: serializer.fromJson<String?>(
        json['countryOfResidenceCode'],
      ),
      maritalStatus: serializer.fromJson<String?>(json['maritalStatus']),
      spouseName: serializer.fromJson<String?>(json['spouseName']),
      hasChildren: serializer.fromJson<bool?>(json['hasChildren']),
      childrenCount: serializer.fromJson<int?>(json['childrenCount']),
      educationLevel: serializer.fromJson<int?>(json['educationLevel']),
      birthCountryCode: serializer.fromJson<String?>(json['birthCountryCode']),
      birthStateCode: serializer.fromJson<String?>(json['birthStateCode']),
      birthStateText: serializer.fromJson<String?>(json['birthStateText']),
      birthCityText: serializer.fromJson<String?>(json['birthCityText']),
      occupationCode: serializer.fromJson<String?>(json['occupationCode']),
      monthlyExpensesSdg: serializer.fromJson<String?>(
        json['monthlyExpensesSdg'],
      ),
      homeCountryCode: serializer.fromJson<String?>(json['homeCountryCode']),
      homeStateCode: serializer.fromJson<String?>(json['homeStateCode']),
      homeStateText: serializer.fromJson<String?>(json['homeStateText']),
      homeLocalityCode: serializer.fromJson<String?>(json['homeLocalityCode']),
      homeLocalityText: serializer.fromJson<String?>(json['homeLocalityText']),
      homeCity: serializer.fromJson<String?>(json['homeCity']),
      homeArea: serializer.fromJson<String?>(json['homeArea']),
      homeStreet: serializer.fromJson<String?>(json['homeStreet']),
      homeBlock: serializer.fromJson<String?>(json['homeBlock']),
      homeHouseNumber: serializer.fromJson<String?>(json['homeHouseNumber']),
      workEmployer: serializer.fromJson<String?>(json['workEmployer']),
      workCountryCode: serializer.fromJson<String?>(json['workCountryCode']),
      workStateCode: serializer.fromJson<String?>(json['workStateCode']),
      workStateText: serializer.fromJson<String?>(json['workStateText']),
      workLocalityCode: serializer.fromJson<String?>(json['workLocalityCode']),
      workLocalityText: serializer.fromJson<String?>(json['workLocalityText']),
      workCity: serializer.fromJson<String?>(json['workCity']),
      workArea: serializer.fromJson<String?>(json['workArea']),
      workStreet: serializer.fromJson<String?>(json['workStreet']),
      workBlock: serializer.fromJson<String?>(json['workBlock']),
      salaryCertificatePath: serializer.fromJson<String?>(
        json['salaryCertificatePath'],
      ),
      salaryCertificateUploadedAt: serializer.fromJson<DateTime?>(
        json['salaryCertificateUploadedAt'],
      ),
      identityType: serializer.fromJson<String?>(json['identityType']),
      updatedAt: serializer.fromJson<DateTime>(json['updatedAt']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'id': serializer.toJson<int>(id),
      'sexDeclared': serializer.toJson<String?>(sexDeclared),
      'ethnicity': serializer.toJson<String?>(ethnicity),
      'countryOfResidenceCode': serializer.toJson<String?>(
        countryOfResidenceCode,
      ),
      'maritalStatus': serializer.toJson<String?>(maritalStatus),
      'spouseName': serializer.toJson<String?>(spouseName),
      'hasChildren': serializer.toJson<bool?>(hasChildren),
      'childrenCount': serializer.toJson<int?>(childrenCount),
      'educationLevel': serializer.toJson<int?>(educationLevel),
      'birthCountryCode': serializer.toJson<String?>(birthCountryCode),
      'birthStateCode': serializer.toJson<String?>(birthStateCode),
      'birthStateText': serializer.toJson<String?>(birthStateText),
      'birthCityText': serializer.toJson<String?>(birthCityText),
      'occupationCode': serializer.toJson<String?>(occupationCode),
      'monthlyExpensesSdg': serializer.toJson<String?>(monthlyExpensesSdg),
      'homeCountryCode': serializer.toJson<String?>(homeCountryCode),
      'homeStateCode': serializer.toJson<String?>(homeStateCode),
      'homeStateText': serializer.toJson<String?>(homeStateText),
      'homeLocalityCode': serializer.toJson<String?>(homeLocalityCode),
      'homeLocalityText': serializer.toJson<String?>(homeLocalityText),
      'homeCity': serializer.toJson<String?>(homeCity),
      'homeArea': serializer.toJson<String?>(homeArea),
      'homeStreet': serializer.toJson<String?>(homeStreet),
      'homeBlock': serializer.toJson<String?>(homeBlock),
      'homeHouseNumber': serializer.toJson<String?>(homeHouseNumber),
      'workEmployer': serializer.toJson<String?>(workEmployer),
      'workCountryCode': serializer.toJson<String?>(workCountryCode),
      'workStateCode': serializer.toJson<String?>(workStateCode),
      'workStateText': serializer.toJson<String?>(workStateText),
      'workLocalityCode': serializer.toJson<String?>(workLocalityCode),
      'workLocalityText': serializer.toJson<String?>(workLocalityText),
      'workCity': serializer.toJson<String?>(workCity),
      'workArea': serializer.toJson<String?>(workArea),
      'workStreet': serializer.toJson<String?>(workStreet),
      'workBlock': serializer.toJson<String?>(workBlock),
      'salaryCertificatePath': serializer.toJson<String?>(
        salaryCertificatePath,
      ),
      'salaryCertificateUploadedAt': serializer.toJson<DateTime?>(
        salaryCertificateUploadedAt,
      ),
      'identityType': serializer.toJson<String?>(identityType),
      'updatedAt': serializer.toJson<DateTime>(updatedAt),
    };
  }

  DataEntryDraftData copyWith({
    int? id,
    Value<String?> sexDeclared = const Value.absent(),
    Value<String?> ethnicity = const Value.absent(),
    Value<String?> countryOfResidenceCode = const Value.absent(),
    Value<String?> maritalStatus = const Value.absent(),
    Value<String?> spouseName = const Value.absent(),
    Value<bool?> hasChildren = const Value.absent(),
    Value<int?> childrenCount = const Value.absent(),
    Value<int?> educationLevel = const Value.absent(),
    Value<String?> birthCountryCode = const Value.absent(),
    Value<String?> birthStateCode = const Value.absent(),
    Value<String?> birthStateText = const Value.absent(),
    Value<String?> birthCityText = const Value.absent(),
    Value<String?> occupationCode = const Value.absent(),
    Value<String?> monthlyExpensesSdg = const Value.absent(),
    Value<String?> homeCountryCode = const Value.absent(),
    Value<String?> homeStateCode = const Value.absent(),
    Value<String?> homeStateText = const Value.absent(),
    Value<String?> homeLocalityCode = const Value.absent(),
    Value<String?> homeLocalityText = const Value.absent(),
    Value<String?> homeCity = const Value.absent(),
    Value<String?> homeArea = const Value.absent(),
    Value<String?> homeStreet = const Value.absent(),
    Value<String?> homeBlock = const Value.absent(),
    Value<String?> homeHouseNumber = const Value.absent(),
    Value<String?> workEmployer = const Value.absent(),
    Value<String?> workCountryCode = const Value.absent(),
    Value<String?> workStateCode = const Value.absent(),
    Value<String?> workStateText = const Value.absent(),
    Value<String?> workLocalityCode = const Value.absent(),
    Value<String?> workLocalityText = const Value.absent(),
    Value<String?> workCity = const Value.absent(),
    Value<String?> workArea = const Value.absent(),
    Value<String?> workStreet = const Value.absent(),
    Value<String?> workBlock = const Value.absent(),
    Value<String?> salaryCertificatePath = const Value.absent(),
    Value<DateTime?> salaryCertificateUploadedAt = const Value.absent(),
    Value<String?> identityType = const Value.absent(),
    DateTime? updatedAt,
  }) => DataEntryDraftData(
    id: id ?? this.id,
    sexDeclared: sexDeclared.present ? sexDeclared.value : this.sexDeclared,
    ethnicity: ethnicity.present ? ethnicity.value : this.ethnicity,
    countryOfResidenceCode: countryOfResidenceCode.present
        ? countryOfResidenceCode.value
        : this.countryOfResidenceCode,
    maritalStatus: maritalStatus.present
        ? maritalStatus.value
        : this.maritalStatus,
    spouseName: spouseName.present ? spouseName.value : this.spouseName,
    hasChildren: hasChildren.present ? hasChildren.value : this.hasChildren,
    childrenCount: childrenCount.present
        ? childrenCount.value
        : this.childrenCount,
    educationLevel: educationLevel.present
        ? educationLevel.value
        : this.educationLevel,
    birthCountryCode: birthCountryCode.present
        ? birthCountryCode.value
        : this.birthCountryCode,
    birthStateCode: birthStateCode.present
        ? birthStateCode.value
        : this.birthStateCode,
    birthStateText: birthStateText.present
        ? birthStateText.value
        : this.birthStateText,
    birthCityText: birthCityText.present
        ? birthCityText.value
        : this.birthCityText,
    occupationCode: occupationCode.present
        ? occupationCode.value
        : this.occupationCode,
    monthlyExpensesSdg: monthlyExpensesSdg.present
        ? monthlyExpensesSdg.value
        : this.monthlyExpensesSdg,
    homeCountryCode: homeCountryCode.present
        ? homeCountryCode.value
        : this.homeCountryCode,
    homeStateCode: homeStateCode.present
        ? homeStateCode.value
        : this.homeStateCode,
    homeStateText: homeStateText.present
        ? homeStateText.value
        : this.homeStateText,
    homeLocalityCode: homeLocalityCode.present
        ? homeLocalityCode.value
        : this.homeLocalityCode,
    homeLocalityText: homeLocalityText.present
        ? homeLocalityText.value
        : this.homeLocalityText,
    homeCity: homeCity.present ? homeCity.value : this.homeCity,
    homeArea: homeArea.present ? homeArea.value : this.homeArea,
    homeStreet: homeStreet.present ? homeStreet.value : this.homeStreet,
    homeBlock: homeBlock.present ? homeBlock.value : this.homeBlock,
    homeHouseNumber: homeHouseNumber.present
        ? homeHouseNumber.value
        : this.homeHouseNumber,
    workEmployer: workEmployer.present ? workEmployer.value : this.workEmployer,
    workCountryCode: workCountryCode.present
        ? workCountryCode.value
        : this.workCountryCode,
    workStateCode: workStateCode.present
        ? workStateCode.value
        : this.workStateCode,
    workStateText: workStateText.present
        ? workStateText.value
        : this.workStateText,
    workLocalityCode: workLocalityCode.present
        ? workLocalityCode.value
        : this.workLocalityCode,
    workLocalityText: workLocalityText.present
        ? workLocalityText.value
        : this.workLocalityText,
    workCity: workCity.present ? workCity.value : this.workCity,
    workArea: workArea.present ? workArea.value : this.workArea,
    workStreet: workStreet.present ? workStreet.value : this.workStreet,
    workBlock: workBlock.present ? workBlock.value : this.workBlock,
    salaryCertificatePath: salaryCertificatePath.present
        ? salaryCertificatePath.value
        : this.salaryCertificatePath,
    salaryCertificateUploadedAt: salaryCertificateUploadedAt.present
        ? salaryCertificateUploadedAt.value
        : this.salaryCertificateUploadedAt,
    identityType: identityType.present ? identityType.value : this.identityType,
    updatedAt: updatedAt ?? this.updatedAt,
  );
  DataEntryDraftData copyWithCompanion(DataEntryDraftCompanion data) {
    return DataEntryDraftData(
      id: data.id.present ? data.id.value : this.id,
      sexDeclared: data.sexDeclared.present
          ? data.sexDeclared.value
          : this.sexDeclared,
      ethnicity: data.ethnicity.present ? data.ethnicity.value : this.ethnicity,
      countryOfResidenceCode: data.countryOfResidenceCode.present
          ? data.countryOfResidenceCode.value
          : this.countryOfResidenceCode,
      maritalStatus: data.maritalStatus.present
          ? data.maritalStatus.value
          : this.maritalStatus,
      spouseName: data.spouseName.present
          ? data.spouseName.value
          : this.spouseName,
      hasChildren: data.hasChildren.present
          ? data.hasChildren.value
          : this.hasChildren,
      childrenCount: data.childrenCount.present
          ? data.childrenCount.value
          : this.childrenCount,
      educationLevel: data.educationLevel.present
          ? data.educationLevel.value
          : this.educationLevel,
      birthCountryCode: data.birthCountryCode.present
          ? data.birthCountryCode.value
          : this.birthCountryCode,
      birthStateCode: data.birthStateCode.present
          ? data.birthStateCode.value
          : this.birthStateCode,
      birthStateText: data.birthStateText.present
          ? data.birthStateText.value
          : this.birthStateText,
      birthCityText: data.birthCityText.present
          ? data.birthCityText.value
          : this.birthCityText,
      occupationCode: data.occupationCode.present
          ? data.occupationCode.value
          : this.occupationCode,
      monthlyExpensesSdg: data.monthlyExpensesSdg.present
          ? data.monthlyExpensesSdg.value
          : this.monthlyExpensesSdg,
      homeCountryCode: data.homeCountryCode.present
          ? data.homeCountryCode.value
          : this.homeCountryCode,
      homeStateCode: data.homeStateCode.present
          ? data.homeStateCode.value
          : this.homeStateCode,
      homeStateText: data.homeStateText.present
          ? data.homeStateText.value
          : this.homeStateText,
      homeLocalityCode: data.homeLocalityCode.present
          ? data.homeLocalityCode.value
          : this.homeLocalityCode,
      homeLocalityText: data.homeLocalityText.present
          ? data.homeLocalityText.value
          : this.homeLocalityText,
      homeCity: data.homeCity.present ? data.homeCity.value : this.homeCity,
      homeArea: data.homeArea.present ? data.homeArea.value : this.homeArea,
      homeStreet: data.homeStreet.present
          ? data.homeStreet.value
          : this.homeStreet,
      homeBlock: data.homeBlock.present ? data.homeBlock.value : this.homeBlock,
      homeHouseNumber: data.homeHouseNumber.present
          ? data.homeHouseNumber.value
          : this.homeHouseNumber,
      workEmployer: data.workEmployer.present
          ? data.workEmployer.value
          : this.workEmployer,
      workCountryCode: data.workCountryCode.present
          ? data.workCountryCode.value
          : this.workCountryCode,
      workStateCode: data.workStateCode.present
          ? data.workStateCode.value
          : this.workStateCode,
      workStateText: data.workStateText.present
          ? data.workStateText.value
          : this.workStateText,
      workLocalityCode: data.workLocalityCode.present
          ? data.workLocalityCode.value
          : this.workLocalityCode,
      workLocalityText: data.workLocalityText.present
          ? data.workLocalityText.value
          : this.workLocalityText,
      workCity: data.workCity.present ? data.workCity.value : this.workCity,
      workArea: data.workArea.present ? data.workArea.value : this.workArea,
      workStreet: data.workStreet.present
          ? data.workStreet.value
          : this.workStreet,
      workBlock: data.workBlock.present ? data.workBlock.value : this.workBlock,
      salaryCertificatePath: data.salaryCertificatePath.present
          ? data.salaryCertificatePath.value
          : this.salaryCertificatePath,
      salaryCertificateUploadedAt: data.salaryCertificateUploadedAt.present
          ? data.salaryCertificateUploadedAt.value
          : this.salaryCertificateUploadedAt,
      identityType: data.identityType.present
          ? data.identityType.value
          : this.identityType,
      updatedAt: data.updatedAt.present ? data.updatedAt.value : this.updatedAt,
    );
  }

  @override
  String toString() {
    return (StringBuffer('DataEntryDraftData(')
          ..write('id: $id, ')
          ..write('sexDeclared: $sexDeclared, ')
          ..write('ethnicity: $ethnicity, ')
          ..write('countryOfResidenceCode: $countryOfResidenceCode, ')
          ..write('maritalStatus: $maritalStatus, ')
          ..write('spouseName: $spouseName, ')
          ..write('hasChildren: $hasChildren, ')
          ..write('childrenCount: $childrenCount, ')
          ..write('educationLevel: $educationLevel, ')
          ..write('birthCountryCode: $birthCountryCode, ')
          ..write('birthStateCode: $birthStateCode, ')
          ..write('birthStateText: $birthStateText, ')
          ..write('birthCityText: $birthCityText, ')
          ..write('occupationCode: $occupationCode, ')
          ..write('monthlyExpensesSdg: $monthlyExpensesSdg, ')
          ..write('homeCountryCode: $homeCountryCode, ')
          ..write('homeStateCode: $homeStateCode, ')
          ..write('homeStateText: $homeStateText, ')
          ..write('homeLocalityCode: $homeLocalityCode, ')
          ..write('homeLocalityText: $homeLocalityText, ')
          ..write('homeCity: $homeCity, ')
          ..write('homeArea: $homeArea, ')
          ..write('homeStreet: $homeStreet, ')
          ..write('homeBlock: $homeBlock, ')
          ..write('homeHouseNumber: $homeHouseNumber, ')
          ..write('workEmployer: $workEmployer, ')
          ..write('workCountryCode: $workCountryCode, ')
          ..write('workStateCode: $workStateCode, ')
          ..write('workStateText: $workStateText, ')
          ..write('workLocalityCode: $workLocalityCode, ')
          ..write('workLocalityText: $workLocalityText, ')
          ..write('workCity: $workCity, ')
          ..write('workArea: $workArea, ')
          ..write('workStreet: $workStreet, ')
          ..write('workBlock: $workBlock, ')
          ..write('salaryCertificatePath: $salaryCertificatePath, ')
          ..write('salaryCertificateUploadedAt: $salaryCertificateUploadedAt, ')
          ..write('identityType: $identityType, ')
          ..write('updatedAt: $updatedAt')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hashAll([
    id,
    sexDeclared,
    ethnicity,
    countryOfResidenceCode,
    maritalStatus,
    spouseName,
    hasChildren,
    childrenCount,
    educationLevel,
    birthCountryCode,
    birthStateCode,
    birthStateText,
    birthCityText,
    occupationCode,
    monthlyExpensesSdg,
    homeCountryCode,
    homeStateCode,
    homeStateText,
    homeLocalityCode,
    homeLocalityText,
    homeCity,
    homeArea,
    homeStreet,
    homeBlock,
    homeHouseNumber,
    workEmployer,
    workCountryCode,
    workStateCode,
    workStateText,
    workLocalityCode,
    workLocalityText,
    workCity,
    workArea,
    workStreet,
    workBlock,
    salaryCertificatePath,
    salaryCertificateUploadedAt,
    identityType,
    updatedAt,
  ]);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is DataEntryDraftData &&
          other.id == this.id &&
          other.sexDeclared == this.sexDeclared &&
          other.ethnicity == this.ethnicity &&
          other.countryOfResidenceCode == this.countryOfResidenceCode &&
          other.maritalStatus == this.maritalStatus &&
          other.spouseName == this.spouseName &&
          other.hasChildren == this.hasChildren &&
          other.childrenCount == this.childrenCount &&
          other.educationLevel == this.educationLevel &&
          other.birthCountryCode == this.birthCountryCode &&
          other.birthStateCode == this.birthStateCode &&
          other.birthStateText == this.birthStateText &&
          other.birthCityText == this.birthCityText &&
          other.occupationCode == this.occupationCode &&
          other.monthlyExpensesSdg == this.monthlyExpensesSdg &&
          other.homeCountryCode == this.homeCountryCode &&
          other.homeStateCode == this.homeStateCode &&
          other.homeStateText == this.homeStateText &&
          other.homeLocalityCode == this.homeLocalityCode &&
          other.homeLocalityText == this.homeLocalityText &&
          other.homeCity == this.homeCity &&
          other.homeArea == this.homeArea &&
          other.homeStreet == this.homeStreet &&
          other.homeBlock == this.homeBlock &&
          other.homeHouseNumber == this.homeHouseNumber &&
          other.workEmployer == this.workEmployer &&
          other.workCountryCode == this.workCountryCode &&
          other.workStateCode == this.workStateCode &&
          other.workStateText == this.workStateText &&
          other.workLocalityCode == this.workLocalityCode &&
          other.workLocalityText == this.workLocalityText &&
          other.workCity == this.workCity &&
          other.workArea == this.workArea &&
          other.workStreet == this.workStreet &&
          other.workBlock == this.workBlock &&
          other.salaryCertificatePath == this.salaryCertificatePath &&
          other.salaryCertificateUploadedAt ==
              this.salaryCertificateUploadedAt &&
          other.identityType == this.identityType &&
          other.updatedAt == this.updatedAt);
}

class DataEntryDraftCompanion extends UpdateCompanion<DataEntryDraftData> {
  final Value<int> id;
  final Value<String?> sexDeclared;
  final Value<String?> ethnicity;
  final Value<String?> countryOfResidenceCode;
  final Value<String?> maritalStatus;
  final Value<String?> spouseName;
  final Value<bool?> hasChildren;
  final Value<int?> childrenCount;
  final Value<int?> educationLevel;
  final Value<String?> birthCountryCode;
  final Value<String?> birthStateCode;
  final Value<String?> birthStateText;
  final Value<String?> birthCityText;
  final Value<String?> occupationCode;
  final Value<String?> monthlyExpensesSdg;
  final Value<String?> homeCountryCode;
  final Value<String?> homeStateCode;
  final Value<String?> homeStateText;
  final Value<String?> homeLocalityCode;
  final Value<String?> homeLocalityText;
  final Value<String?> homeCity;
  final Value<String?> homeArea;
  final Value<String?> homeStreet;
  final Value<String?> homeBlock;
  final Value<String?> homeHouseNumber;
  final Value<String?> workEmployer;
  final Value<String?> workCountryCode;
  final Value<String?> workStateCode;
  final Value<String?> workStateText;
  final Value<String?> workLocalityCode;
  final Value<String?> workLocalityText;
  final Value<String?> workCity;
  final Value<String?> workArea;
  final Value<String?> workStreet;
  final Value<String?> workBlock;
  final Value<String?> salaryCertificatePath;
  final Value<DateTime?> salaryCertificateUploadedAt;
  final Value<String?> identityType;
  final Value<DateTime> updatedAt;
  const DataEntryDraftCompanion({
    this.id = const Value.absent(),
    this.sexDeclared = const Value.absent(),
    this.ethnicity = const Value.absent(),
    this.countryOfResidenceCode = const Value.absent(),
    this.maritalStatus = const Value.absent(),
    this.spouseName = const Value.absent(),
    this.hasChildren = const Value.absent(),
    this.childrenCount = const Value.absent(),
    this.educationLevel = const Value.absent(),
    this.birthCountryCode = const Value.absent(),
    this.birthStateCode = const Value.absent(),
    this.birthStateText = const Value.absent(),
    this.birthCityText = const Value.absent(),
    this.occupationCode = const Value.absent(),
    this.monthlyExpensesSdg = const Value.absent(),
    this.homeCountryCode = const Value.absent(),
    this.homeStateCode = const Value.absent(),
    this.homeStateText = const Value.absent(),
    this.homeLocalityCode = const Value.absent(),
    this.homeLocalityText = const Value.absent(),
    this.homeCity = const Value.absent(),
    this.homeArea = const Value.absent(),
    this.homeStreet = const Value.absent(),
    this.homeBlock = const Value.absent(),
    this.homeHouseNumber = const Value.absent(),
    this.workEmployer = const Value.absent(),
    this.workCountryCode = const Value.absent(),
    this.workStateCode = const Value.absent(),
    this.workStateText = const Value.absent(),
    this.workLocalityCode = const Value.absent(),
    this.workLocalityText = const Value.absent(),
    this.workCity = const Value.absent(),
    this.workArea = const Value.absent(),
    this.workStreet = const Value.absent(),
    this.workBlock = const Value.absent(),
    this.salaryCertificatePath = const Value.absent(),
    this.salaryCertificateUploadedAt = const Value.absent(),
    this.identityType = const Value.absent(),
    this.updatedAt = const Value.absent(),
  });
  DataEntryDraftCompanion.insert({
    this.id = const Value.absent(),
    this.sexDeclared = const Value.absent(),
    this.ethnicity = const Value.absent(),
    this.countryOfResidenceCode = const Value.absent(),
    this.maritalStatus = const Value.absent(),
    this.spouseName = const Value.absent(),
    this.hasChildren = const Value.absent(),
    this.childrenCount = const Value.absent(),
    this.educationLevel = const Value.absent(),
    this.birthCountryCode = const Value.absent(),
    this.birthStateCode = const Value.absent(),
    this.birthStateText = const Value.absent(),
    this.birthCityText = const Value.absent(),
    this.occupationCode = const Value.absent(),
    this.monthlyExpensesSdg = const Value.absent(),
    this.homeCountryCode = const Value.absent(),
    this.homeStateCode = const Value.absent(),
    this.homeStateText = const Value.absent(),
    this.homeLocalityCode = const Value.absent(),
    this.homeLocalityText = const Value.absent(),
    this.homeCity = const Value.absent(),
    this.homeArea = const Value.absent(),
    this.homeStreet = const Value.absent(),
    this.homeBlock = const Value.absent(),
    this.homeHouseNumber = const Value.absent(),
    this.workEmployer = const Value.absent(),
    this.workCountryCode = const Value.absent(),
    this.workStateCode = const Value.absent(),
    this.workStateText = const Value.absent(),
    this.workLocalityCode = const Value.absent(),
    this.workLocalityText = const Value.absent(),
    this.workCity = const Value.absent(),
    this.workArea = const Value.absent(),
    this.workStreet = const Value.absent(),
    this.workBlock = const Value.absent(),
    this.salaryCertificatePath = const Value.absent(),
    this.salaryCertificateUploadedAt = const Value.absent(),
    this.identityType = const Value.absent(),
    required DateTime updatedAt,
  }) : updatedAt = Value(updatedAt);
  static Insertable<DataEntryDraftData> custom({
    Expression<int>? id,
    Expression<String>? sexDeclared,
    Expression<String>? ethnicity,
    Expression<String>? countryOfResidenceCode,
    Expression<String>? maritalStatus,
    Expression<String>? spouseName,
    Expression<bool>? hasChildren,
    Expression<int>? childrenCount,
    Expression<int>? educationLevel,
    Expression<String>? birthCountryCode,
    Expression<String>? birthStateCode,
    Expression<String>? birthStateText,
    Expression<String>? birthCityText,
    Expression<String>? occupationCode,
    Expression<String>? monthlyExpensesSdg,
    Expression<String>? homeCountryCode,
    Expression<String>? homeStateCode,
    Expression<String>? homeStateText,
    Expression<String>? homeLocalityCode,
    Expression<String>? homeLocalityText,
    Expression<String>? homeCity,
    Expression<String>? homeArea,
    Expression<String>? homeStreet,
    Expression<String>? homeBlock,
    Expression<String>? homeHouseNumber,
    Expression<String>? workEmployer,
    Expression<String>? workCountryCode,
    Expression<String>? workStateCode,
    Expression<String>? workStateText,
    Expression<String>? workLocalityCode,
    Expression<String>? workLocalityText,
    Expression<String>? workCity,
    Expression<String>? workArea,
    Expression<String>? workStreet,
    Expression<String>? workBlock,
    Expression<String>? salaryCertificatePath,
    Expression<DateTime>? salaryCertificateUploadedAt,
    Expression<String>? identityType,
    Expression<DateTime>? updatedAt,
  }) {
    return RawValuesInsertable({
      if (id != null) 'id': id,
      if (sexDeclared != null) 'sex_declared': sexDeclared,
      if (ethnicity != null) 'ethnicity': ethnicity,
      if (countryOfResidenceCode != null)
        'country_of_residence_code': countryOfResidenceCode,
      if (maritalStatus != null) 'marital_status': maritalStatus,
      if (spouseName != null) 'spouse_name': spouseName,
      if (hasChildren != null) 'has_children': hasChildren,
      if (childrenCount != null) 'children_count': childrenCount,
      if (educationLevel != null) 'education_level': educationLevel,
      if (birthCountryCode != null) 'birth_country_code': birthCountryCode,
      if (birthStateCode != null) 'birth_state_code': birthStateCode,
      if (birthStateText != null) 'birth_state_text': birthStateText,
      if (birthCityText != null) 'birth_city_text': birthCityText,
      if (occupationCode != null) 'occupation_code': occupationCode,
      if (monthlyExpensesSdg != null)
        'monthly_expenses_sdg': monthlyExpensesSdg,
      if (homeCountryCode != null) 'home_country_code': homeCountryCode,
      if (homeStateCode != null) 'home_state_code': homeStateCode,
      if (homeStateText != null) 'home_state_text': homeStateText,
      if (homeLocalityCode != null) 'home_locality_code': homeLocalityCode,
      if (homeLocalityText != null) 'home_locality_text': homeLocalityText,
      if (homeCity != null) 'home_city': homeCity,
      if (homeArea != null) 'home_area': homeArea,
      if (homeStreet != null) 'home_street': homeStreet,
      if (homeBlock != null) 'home_block': homeBlock,
      if (homeHouseNumber != null) 'home_house_number': homeHouseNumber,
      if (workEmployer != null) 'work_employer': workEmployer,
      if (workCountryCode != null) 'work_country_code': workCountryCode,
      if (workStateCode != null) 'work_state_code': workStateCode,
      if (workStateText != null) 'work_state_text': workStateText,
      if (workLocalityCode != null) 'work_locality_code': workLocalityCode,
      if (workLocalityText != null) 'work_locality_text': workLocalityText,
      if (workCity != null) 'work_city': workCity,
      if (workArea != null) 'work_area': workArea,
      if (workStreet != null) 'work_street': workStreet,
      if (workBlock != null) 'work_block': workBlock,
      if (salaryCertificatePath != null)
        'salary_certificate_path': salaryCertificatePath,
      if (salaryCertificateUploadedAt != null)
        'salary_certificate_uploaded_at': salaryCertificateUploadedAt,
      if (identityType != null) 'identity_type': identityType,
      if (updatedAt != null) 'updated_at': updatedAt,
    });
  }

  DataEntryDraftCompanion copyWith({
    Value<int>? id,
    Value<String?>? sexDeclared,
    Value<String?>? ethnicity,
    Value<String?>? countryOfResidenceCode,
    Value<String?>? maritalStatus,
    Value<String?>? spouseName,
    Value<bool?>? hasChildren,
    Value<int?>? childrenCount,
    Value<int?>? educationLevel,
    Value<String?>? birthCountryCode,
    Value<String?>? birthStateCode,
    Value<String?>? birthStateText,
    Value<String?>? birthCityText,
    Value<String?>? occupationCode,
    Value<String?>? monthlyExpensesSdg,
    Value<String?>? homeCountryCode,
    Value<String?>? homeStateCode,
    Value<String?>? homeStateText,
    Value<String?>? homeLocalityCode,
    Value<String?>? homeLocalityText,
    Value<String?>? homeCity,
    Value<String?>? homeArea,
    Value<String?>? homeStreet,
    Value<String?>? homeBlock,
    Value<String?>? homeHouseNumber,
    Value<String?>? workEmployer,
    Value<String?>? workCountryCode,
    Value<String?>? workStateCode,
    Value<String?>? workStateText,
    Value<String?>? workLocalityCode,
    Value<String?>? workLocalityText,
    Value<String?>? workCity,
    Value<String?>? workArea,
    Value<String?>? workStreet,
    Value<String?>? workBlock,
    Value<String?>? salaryCertificatePath,
    Value<DateTime?>? salaryCertificateUploadedAt,
    Value<String?>? identityType,
    Value<DateTime>? updatedAt,
  }) {
    return DataEntryDraftCompanion(
      id: id ?? this.id,
      sexDeclared: sexDeclared ?? this.sexDeclared,
      ethnicity: ethnicity ?? this.ethnicity,
      countryOfResidenceCode:
          countryOfResidenceCode ?? this.countryOfResidenceCode,
      maritalStatus: maritalStatus ?? this.maritalStatus,
      spouseName: spouseName ?? this.spouseName,
      hasChildren: hasChildren ?? this.hasChildren,
      childrenCount: childrenCount ?? this.childrenCount,
      educationLevel: educationLevel ?? this.educationLevel,
      birthCountryCode: birthCountryCode ?? this.birthCountryCode,
      birthStateCode: birthStateCode ?? this.birthStateCode,
      birthStateText: birthStateText ?? this.birthStateText,
      birthCityText: birthCityText ?? this.birthCityText,
      occupationCode: occupationCode ?? this.occupationCode,
      monthlyExpensesSdg: monthlyExpensesSdg ?? this.monthlyExpensesSdg,
      homeCountryCode: homeCountryCode ?? this.homeCountryCode,
      homeStateCode: homeStateCode ?? this.homeStateCode,
      homeStateText: homeStateText ?? this.homeStateText,
      homeLocalityCode: homeLocalityCode ?? this.homeLocalityCode,
      homeLocalityText: homeLocalityText ?? this.homeLocalityText,
      homeCity: homeCity ?? this.homeCity,
      homeArea: homeArea ?? this.homeArea,
      homeStreet: homeStreet ?? this.homeStreet,
      homeBlock: homeBlock ?? this.homeBlock,
      homeHouseNumber: homeHouseNumber ?? this.homeHouseNumber,
      workEmployer: workEmployer ?? this.workEmployer,
      workCountryCode: workCountryCode ?? this.workCountryCode,
      workStateCode: workStateCode ?? this.workStateCode,
      workStateText: workStateText ?? this.workStateText,
      workLocalityCode: workLocalityCode ?? this.workLocalityCode,
      workLocalityText: workLocalityText ?? this.workLocalityText,
      workCity: workCity ?? this.workCity,
      workArea: workArea ?? this.workArea,
      workStreet: workStreet ?? this.workStreet,
      workBlock: workBlock ?? this.workBlock,
      salaryCertificatePath:
          salaryCertificatePath ?? this.salaryCertificatePath,
      salaryCertificateUploadedAt:
          salaryCertificateUploadedAt ?? this.salaryCertificateUploadedAt,
      identityType: identityType ?? this.identityType,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (id.present) {
      map['id'] = Variable<int>(id.value);
    }
    if (sexDeclared.present) {
      map['sex_declared'] = Variable<String>(sexDeclared.value);
    }
    if (ethnicity.present) {
      map['ethnicity'] = Variable<String>(ethnicity.value);
    }
    if (countryOfResidenceCode.present) {
      map['country_of_residence_code'] = Variable<String>(
        countryOfResidenceCode.value,
      );
    }
    if (maritalStatus.present) {
      map['marital_status'] = Variable<String>(maritalStatus.value);
    }
    if (spouseName.present) {
      map['spouse_name'] = Variable<String>(spouseName.value);
    }
    if (hasChildren.present) {
      map['has_children'] = Variable<bool>(hasChildren.value);
    }
    if (childrenCount.present) {
      map['children_count'] = Variable<int>(childrenCount.value);
    }
    if (educationLevel.present) {
      map['education_level'] = Variable<int>(educationLevel.value);
    }
    if (birthCountryCode.present) {
      map['birth_country_code'] = Variable<String>(birthCountryCode.value);
    }
    if (birthStateCode.present) {
      map['birth_state_code'] = Variable<String>(birthStateCode.value);
    }
    if (birthStateText.present) {
      map['birth_state_text'] = Variable<String>(birthStateText.value);
    }
    if (birthCityText.present) {
      map['birth_city_text'] = Variable<String>(birthCityText.value);
    }
    if (occupationCode.present) {
      map['occupation_code'] = Variable<String>(occupationCode.value);
    }
    if (monthlyExpensesSdg.present) {
      map['monthly_expenses_sdg'] = Variable<String>(monthlyExpensesSdg.value);
    }
    if (homeCountryCode.present) {
      map['home_country_code'] = Variable<String>(homeCountryCode.value);
    }
    if (homeStateCode.present) {
      map['home_state_code'] = Variable<String>(homeStateCode.value);
    }
    if (homeStateText.present) {
      map['home_state_text'] = Variable<String>(homeStateText.value);
    }
    if (homeLocalityCode.present) {
      map['home_locality_code'] = Variable<String>(homeLocalityCode.value);
    }
    if (homeLocalityText.present) {
      map['home_locality_text'] = Variable<String>(homeLocalityText.value);
    }
    if (homeCity.present) {
      map['home_city'] = Variable<String>(homeCity.value);
    }
    if (homeArea.present) {
      map['home_area'] = Variable<String>(homeArea.value);
    }
    if (homeStreet.present) {
      map['home_street'] = Variable<String>(homeStreet.value);
    }
    if (homeBlock.present) {
      map['home_block'] = Variable<String>(homeBlock.value);
    }
    if (homeHouseNumber.present) {
      map['home_house_number'] = Variable<String>(homeHouseNumber.value);
    }
    if (workEmployer.present) {
      map['work_employer'] = Variable<String>(workEmployer.value);
    }
    if (workCountryCode.present) {
      map['work_country_code'] = Variable<String>(workCountryCode.value);
    }
    if (workStateCode.present) {
      map['work_state_code'] = Variable<String>(workStateCode.value);
    }
    if (workStateText.present) {
      map['work_state_text'] = Variable<String>(workStateText.value);
    }
    if (workLocalityCode.present) {
      map['work_locality_code'] = Variable<String>(workLocalityCode.value);
    }
    if (workLocalityText.present) {
      map['work_locality_text'] = Variable<String>(workLocalityText.value);
    }
    if (workCity.present) {
      map['work_city'] = Variable<String>(workCity.value);
    }
    if (workArea.present) {
      map['work_area'] = Variable<String>(workArea.value);
    }
    if (workStreet.present) {
      map['work_street'] = Variable<String>(workStreet.value);
    }
    if (workBlock.present) {
      map['work_block'] = Variable<String>(workBlock.value);
    }
    if (salaryCertificatePath.present) {
      map['salary_certificate_path'] = Variable<String>(
        salaryCertificatePath.value,
      );
    }
    if (salaryCertificateUploadedAt.present) {
      map['salary_certificate_uploaded_at'] = Variable<DateTime>(
        salaryCertificateUploadedAt.value,
      );
    }
    if (identityType.present) {
      map['identity_type'] = Variable<String>(identityType.value);
    }
    if (updatedAt.present) {
      map['updated_at'] = Variable<DateTime>(updatedAt.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('DataEntryDraftCompanion(')
          ..write('id: $id, ')
          ..write('sexDeclared: $sexDeclared, ')
          ..write('ethnicity: $ethnicity, ')
          ..write('countryOfResidenceCode: $countryOfResidenceCode, ')
          ..write('maritalStatus: $maritalStatus, ')
          ..write('spouseName: $spouseName, ')
          ..write('hasChildren: $hasChildren, ')
          ..write('childrenCount: $childrenCount, ')
          ..write('educationLevel: $educationLevel, ')
          ..write('birthCountryCode: $birthCountryCode, ')
          ..write('birthStateCode: $birthStateCode, ')
          ..write('birthStateText: $birthStateText, ')
          ..write('birthCityText: $birthCityText, ')
          ..write('occupationCode: $occupationCode, ')
          ..write('monthlyExpensesSdg: $monthlyExpensesSdg, ')
          ..write('homeCountryCode: $homeCountryCode, ')
          ..write('homeStateCode: $homeStateCode, ')
          ..write('homeStateText: $homeStateText, ')
          ..write('homeLocalityCode: $homeLocalityCode, ')
          ..write('homeLocalityText: $homeLocalityText, ')
          ..write('homeCity: $homeCity, ')
          ..write('homeArea: $homeArea, ')
          ..write('homeStreet: $homeStreet, ')
          ..write('homeBlock: $homeBlock, ')
          ..write('homeHouseNumber: $homeHouseNumber, ')
          ..write('workEmployer: $workEmployer, ')
          ..write('workCountryCode: $workCountryCode, ')
          ..write('workStateCode: $workStateCode, ')
          ..write('workStateText: $workStateText, ')
          ..write('workLocalityCode: $workLocalityCode, ')
          ..write('workLocalityText: $workLocalityText, ')
          ..write('workCity: $workCity, ')
          ..write('workArea: $workArea, ')
          ..write('workStreet: $workStreet, ')
          ..write('workBlock: $workBlock, ')
          ..write('salaryCertificatePath: $salaryCertificatePath, ')
          ..write('salaryCertificateUploadedAt: $salaryCertificateUploadedAt, ')
          ..write('identityType: $identityType, ')
          ..write('updatedAt: $updatedAt')
          ..write(')'))
        .toString();
  }
}

class $DataEntryIncomeSourcesTable extends DataEntryIncomeSources
    with TableInfo<$DataEntryIncomeSourcesTable, DataEntryIncomeSource> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $DataEntryIncomeSourcesTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _codeMeta = const VerificationMeta('code');
  @override
  late final GeneratedColumn<String> code = GeneratedColumn<String>(
    'code',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _isPrimaryMeta = const VerificationMeta(
    'isPrimary',
  );
  @override
  late final GeneratedColumn<bool> isPrimary = GeneratedColumn<bool>(
    'is_primary',
    aliasedName,
    false,
    type: DriftSqlType.bool,
    requiredDuringInsert: false,
    defaultConstraints: GeneratedColumn.constraintIsAlways(
      'CHECK ("is_primary" IN (0, 1))',
    ),
    defaultValue: const Constant(false),
  );
  static const VerificationMeta _otherTextMeta = const VerificationMeta(
    'otherText',
  );
  @override
  late final GeneratedColumn<String> otherText = GeneratedColumn<String>(
    'other_text',
    aliasedName,
    true,
    type: DriftSqlType.string,
    requiredDuringInsert: false,
  );
  @override
  List<GeneratedColumn> get $columns => [code, isPrimary, otherText];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'data_entry_income_sources';
  @override
  VerificationContext validateIntegrity(
    Insertable<DataEntryIncomeSource> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('code')) {
      context.handle(
        _codeMeta,
        code.isAcceptableOrUnknown(data['code']!, _codeMeta),
      );
    } else if (isInserting) {
      context.missing(_codeMeta);
    }
    if (data.containsKey('is_primary')) {
      context.handle(
        _isPrimaryMeta,
        isPrimary.isAcceptableOrUnknown(data['is_primary']!, _isPrimaryMeta),
      );
    }
    if (data.containsKey('other_text')) {
      context.handle(
        _otherTextMeta,
        otherText.isAcceptableOrUnknown(data['other_text']!, _otherTextMeta),
      );
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {code};
  @override
  DataEntryIncomeSource map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return DataEntryIncomeSource(
      code: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}code'],
      )!,
      isPrimary: attachedDatabase.typeMapping.read(
        DriftSqlType.bool,
        data['${effectivePrefix}is_primary'],
      )!,
      otherText: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}other_text'],
      ),
    );
  }

  @override
  $DataEntryIncomeSourcesTable createAlias(String alias) {
    return $DataEntryIncomeSourcesTable(attachedDatabase, alias);
  }
}

class DataEntryIncomeSource extends DataClass
    implements Insertable<DataEntryIncomeSource> {
  final String code;
  final bool isPrimary;
  final String? otherText;
  const DataEntryIncomeSource({
    required this.code,
    required this.isPrimary,
    this.otherText,
  });
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['code'] = Variable<String>(code);
    map['is_primary'] = Variable<bool>(isPrimary);
    if (!nullToAbsent || otherText != null) {
      map['other_text'] = Variable<String>(otherText);
    }
    return map;
  }

  DataEntryIncomeSourcesCompanion toCompanion(bool nullToAbsent) {
    return DataEntryIncomeSourcesCompanion(
      code: Value(code),
      isPrimary: Value(isPrimary),
      otherText: otherText == null && nullToAbsent
          ? const Value.absent()
          : Value(otherText),
    );
  }

  factory DataEntryIncomeSource.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return DataEntryIncomeSource(
      code: serializer.fromJson<String>(json['code']),
      isPrimary: serializer.fromJson<bool>(json['isPrimary']),
      otherText: serializer.fromJson<String?>(json['otherText']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'code': serializer.toJson<String>(code),
      'isPrimary': serializer.toJson<bool>(isPrimary),
      'otherText': serializer.toJson<String?>(otherText),
    };
  }

  DataEntryIncomeSource copyWith({
    String? code,
    bool? isPrimary,
    Value<String?> otherText = const Value.absent(),
  }) => DataEntryIncomeSource(
    code: code ?? this.code,
    isPrimary: isPrimary ?? this.isPrimary,
    otherText: otherText.present ? otherText.value : this.otherText,
  );
  DataEntryIncomeSource copyWithCompanion(
    DataEntryIncomeSourcesCompanion data,
  ) {
    return DataEntryIncomeSource(
      code: data.code.present ? data.code.value : this.code,
      isPrimary: data.isPrimary.present ? data.isPrimary.value : this.isPrimary,
      otherText: data.otherText.present ? data.otherText.value : this.otherText,
    );
  }

  @override
  String toString() {
    return (StringBuffer('DataEntryIncomeSource(')
          ..write('code: $code, ')
          ..write('isPrimary: $isPrimary, ')
          ..write('otherText: $otherText')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(code, isPrimary, otherText);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is DataEntryIncomeSource &&
          other.code == this.code &&
          other.isPrimary == this.isPrimary &&
          other.otherText == this.otherText);
}

class DataEntryIncomeSourcesCompanion
    extends UpdateCompanion<DataEntryIncomeSource> {
  final Value<String> code;
  final Value<bool> isPrimary;
  final Value<String?> otherText;
  final Value<int> rowid;
  const DataEntryIncomeSourcesCompanion({
    this.code = const Value.absent(),
    this.isPrimary = const Value.absent(),
    this.otherText = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  DataEntryIncomeSourcesCompanion.insert({
    required String code,
    this.isPrimary = const Value.absent(),
    this.otherText = const Value.absent(),
    this.rowid = const Value.absent(),
  }) : code = Value(code);
  static Insertable<DataEntryIncomeSource> custom({
    Expression<String>? code,
    Expression<bool>? isPrimary,
    Expression<String>? otherText,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (code != null) 'code': code,
      if (isPrimary != null) 'is_primary': isPrimary,
      if (otherText != null) 'other_text': otherText,
      if (rowid != null) 'rowid': rowid,
    });
  }

  DataEntryIncomeSourcesCompanion copyWith({
    Value<String>? code,
    Value<bool>? isPrimary,
    Value<String?>? otherText,
    Value<int>? rowid,
  }) {
    return DataEntryIncomeSourcesCompanion(
      code: code ?? this.code,
      isPrimary: isPrimary ?? this.isPrimary,
      otherText: otherText ?? this.otherText,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (code.present) {
      map['code'] = Variable<String>(code.value);
    }
    if (isPrimary.present) {
      map['is_primary'] = Variable<bool>(isPrimary.value);
    }
    if (otherText.present) {
      map['other_text'] = Variable<String>(otherText.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('DataEntryIncomeSourcesCompanion(')
          ..write('code: $code, ')
          ..write('isPrimary: $isPrimary, ')
          ..write('otherText: $otherText, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

class $PendingStageSyncTable extends PendingStageSync
    with TableInfo<$PendingStageSyncTable, PendingStageSyncData> {
  @override
  final GeneratedDatabase attachedDatabase;
  final String? _alias;
  $PendingStageSyncTable(this.attachedDatabase, [this._alias]);
  static const VerificationMeta _stageMeta = const VerificationMeta('stage');
  @override
  late final GeneratedColumn<String> stage = GeneratedColumn<String>(
    'stage',
    aliasedName,
    false,
    type: DriftSqlType.string,
    requiredDuringInsert: true,
  );
  static const VerificationMeta _queuedAtMeta = const VerificationMeta(
    'queuedAt',
  );
  @override
  late final GeneratedColumn<DateTime> queuedAt = GeneratedColumn<DateTime>(
    'queued_at',
    aliasedName,
    false,
    type: DriftSqlType.dateTime,
    requiredDuringInsert: true,
  );
  @override
  List<GeneratedColumn> get $columns => [stage, queuedAt];
  @override
  String get aliasedName => _alias ?? actualTableName;
  @override
  String get actualTableName => $name;
  static const String $name = 'pending_stage_sync';
  @override
  VerificationContext validateIntegrity(
    Insertable<PendingStageSyncData> instance, {
    bool isInserting = false,
  }) {
    final context = VerificationContext();
    final data = instance.toColumns(true);
    if (data.containsKey('stage')) {
      context.handle(
        _stageMeta,
        stage.isAcceptableOrUnknown(data['stage']!, _stageMeta),
      );
    } else if (isInserting) {
      context.missing(_stageMeta);
    }
    if (data.containsKey('queued_at')) {
      context.handle(
        _queuedAtMeta,
        queuedAt.isAcceptableOrUnknown(data['queued_at']!, _queuedAtMeta),
      );
    } else if (isInserting) {
      context.missing(_queuedAtMeta);
    }
    return context;
  }

  @override
  Set<GeneratedColumn> get $primaryKey => {stage};
  @override
  PendingStageSyncData map(Map<String, dynamic> data, {String? tablePrefix}) {
    final effectivePrefix = tablePrefix != null ? '$tablePrefix.' : '';
    return PendingStageSyncData(
      stage: attachedDatabase.typeMapping.read(
        DriftSqlType.string,
        data['${effectivePrefix}stage'],
      )!,
      queuedAt: attachedDatabase.typeMapping.read(
        DriftSqlType.dateTime,
        data['${effectivePrefix}queued_at'],
      )!,
    );
  }

  @override
  $PendingStageSyncTable createAlias(String alias) {
    return $PendingStageSyncTable(attachedDatabase, alias);
  }
}

class PendingStageSyncData extends DataClass
    implements Insertable<PendingStageSyncData> {
  final String stage;
  final DateTime queuedAt;
  const PendingStageSyncData({required this.stage, required this.queuedAt});
  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    map['stage'] = Variable<String>(stage);
    map['queued_at'] = Variable<DateTime>(queuedAt);
    return map;
  }

  PendingStageSyncCompanion toCompanion(bool nullToAbsent) {
    return PendingStageSyncCompanion(
      stage: Value(stage),
      queuedAt: Value(queuedAt),
    );
  }

  factory PendingStageSyncData.fromJson(
    Map<String, dynamic> json, {
    ValueSerializer? serializer,
  }) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return PendingStageSyncData(
      stage: serializer.fromJson<String>(json['stage']),
      queuedAt: serializer.fromJson<DateTime>(json['queuedAt']),
    );
  }
  @override
  Map<String, dynamic> toJson({ValueSerializer? serializer}) {
    serializer ??= driftRuntimeOptions.defaultSerializer;
    return <String, dynamic>{
      'stage': serializer.toJson<String>(stage),
      'queuedAt': serializer.toJson<DateTime>(queuedAt),
    };
  }

  PendingStageSyncData copyWith({String? stage, DateTime? queuedAt}) =>
      PendingStageSyncData(
        stage: stage ?? this.stage,
        queuedAt: queuedAt ?? this.queuedAt,
      );
  PendingStageSyncData copyWithCompanion(PendingStageSyncCompanion data) {
    return PendingStageSyncData(
      stage: data.stage.present ? data.stage.value : this.stage,
      queuedAt: data.queuedAt.present ? data.queuedAt.value : this.queuedAt,
    );
  }

  @override
  String toString() {
    return (StringBuffer('PendingStageSyncData(')
          ..write('stage: $stage, ')
          ..write('queuedAt: $queuedAt')
          ..write(')'))
        .toString();
  }

  @override
  int get hashCode => Object.hash(stage, queuedAt);
  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is PendingStageSyncData &&
          other.stage == this.stage &&
          other.queuedAt == this.queuedAt);
}

class PendingStageSyncCompanion extends UpdateCompanion<PendingStageSyncData> {
  final Value<String> stage;
  final Value<DateTime> queuedAt;
  final Value<int> rowid;
  const PendingStageSyncCompanion({
    this.stage = const Value.absent(),
    this.queuedAt = const Value.absent(),
    this.rowid = const Value.absent(),
  });
  PendingStageSyncCompanion.insert({
    required String stage,
    required DateTime queuedAt,
    this.rowid = const Value.absent(),
  }) : stage = Value(stage),
       queuedAt = Value(queuedAt);
  static Insertable<PendingStageSyncData> custom({
    Expression<String>? stage,
    Expression<DateTime>? queuedAt,
    Expression<int>? rowid,
  }) {
    return RawValuesInsertable({
      if (stage != null) 'stage': stage,
      if (queuedAt != null) 'queued_at': queuedAt,
      if (rowid != null) 'rowid': rowid,
    });
  }

  PendingStageSyncCompanion copyWith({
    Value<String>? stage,
    Value<DateTime>? queuedAt,
    Value<int>? rowid,
  }) {
    return PendingStageSyncCompanion(
      stage: stage ?? this.stage,
      queuedAt: queuedAt ?? this.queuedAt,
      rowid: rowid ?? this.rowid,
    );
  }

  @override
  Map<String, Expression> toColumns(bool nullToAbsent) {
    final map = <String, Expression>{};
    if (stage.present) {
      map['stage'] = Variable<String>(stage.value);
    }
    if (queuedAt.present) {
      map['queued_at'] = Variable<DateTime>(queuedAt.value);
    }
    if (rowid.present) {
      map['rowid'] = Variable<int>(rowid.value);
    }
    return map;
  }

  @override
  String toString() {
    return (StringBuffer('PendingStageSyncCompanion(')
          ..write('stage: $stage, ')
          ..write('queuedAt: $queuedAt, ')
          ..write('rowid: $rowid')
          ..write(')'))
        .toString();
  }
}

abstract class _$SessionDatabase extends GeneratedDatabase {
  _$SessionDatabase(QueryExecutor e) : super(e);
  $SessionDatabaseManager get managers => $SessionDatabaseManager(this);
  late final $PinnedReferenceVersionsTable pinnedReferenceVersions =
      $PinnedReferenceVersionsTable(this);
  late final $LocalDraftTable localDraft = $LocalDraftTable(this);
  late final $LocalProgressTable localProgress = $LocalProgressTable(this);
  late final $DataEntryDraftTable dataEntryDraft = $DataEntryDraftTable(this);
  late final $DataEntryIncomeSourcesTable dataEntryIncomeSources =
      $DataEntryIncomeSourcesTable(this);
  late final $PendingStageSyncTable pendingStageSync = $PendingStageSyncTable(
    this,
  );
  @override
  Iterable<TableInfo<Table, Object?>> get allTables =>
      allSchemaEntities.whereType<TableInfo<Table, Object?>>();
  @override
  List<DatabaseSchemaEntity> get allSchemaEntities => [
    pinnedReferenceVersions,
    localDraft,
    localProgress,
    dataEntryDraft,
    dataEntryIncomeSources,
    pendingStageSync,
  ];
}

typedef $$PinnedReferenceVersionsTableCreateCompanionBuilder =
    PinnedReferenceVersionsCompanion Function({
      required String listCode,
      required int pinnedVersion,
      required DateTime pinnedAt,
      Value<int> rowid,
    });
typedef $$PinnedReferenceVersionsTableUpdateCompanionBuilder =
    PinnedReferenceVersionsCompanion Function({
      Value<String> listCode,
      Value<int> pinnedVersion,
      Value<DateTime> pinnedAt,
      Value<int> rowid,
    });

class $$PinnedReferenceVersionsTableFilterComposer
    extends Composer<_$SessionDatabase, $PinnedReferenceVersionsTable> {
  $$PinnedReferenceVersionsTableFilterComposer({
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

  ColumnFilters<int> get pinnedVersion => $composableBuilder(
    column: $table.pinnedVersion,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get pinnedAt => $composableBuilder(
    column: $table.pinnedAt,
    builder: (column) => ColumnFilters(column),
  );
}

class $$PinnedReferenceVersionsTableOrderingComposer
    extends Composer<_$SessionDatabase, $PinnedReferenceVersionsTable> {
  $$PinnedReferenceVersionsTableOrderingComposer({
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

  ColumnOrderings<int> get pinnedVersion => $composableBuilder(
    column: $table.pinnedVersion,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get pinnedAt => $composableBuilder(
    column: $table.pinnedAt,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$PinnedReferenceVersionsTableAnnotationComposer
    extends Composer<_$SessionDatabase, $PinnedReferenceVersionsTable> {
  $$PinnedReferenceVersionsTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get listCode =>
      $composableBuilder(column: $table.listCode, builder: (column) => column);

  GeneratedColumn<int> get pinnedVersion => $composableBuilder(
    column: $table.pinnedVersion,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get pinnedAt =>
      $composableBuilder(column: $table.pinnedAt, builder: (column) => column);
}

class $$PinnedReferenceVersionsTableTableManager
    extends
        RootTableManager<
          _$SessionDatabase,
          $PinnedReferenceVersionsTable,
          PinnedReferenceVersion,
          $$PinnedReferenceVersionsTableFilterComposer,
          $$PinnedReferenceVersionsTableOrderingComposer,
          $$PinnedReferenceVersionsTableAnnotationComposer,
          $$PinnedReferenceVersionsTableCreateCompanionBuilder,
          $$PinnedReferenceVersionsTableUpdateCompanionBuilder,
          (
            PinnedReferenceVersion,
            BaseReferences<
              _$SessionDatabase,
              $PinnedReferenceVersionsTable,
              PinnedReferenceVersion
            >,
          ),
          PinnedReferenceVersion,
          PrefetchHooks Function()
        > {
  $$PinnedReferenceVersionsTableTableManager(
    _$SessionDatabase db,
    $PinnedReferenceVersionsTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$PinnedReferenceVersionsTableFilterComposer(
                $db: db,
                $table: table,
              ),
          createOrderingComposer: () =>
              $$PinnedReferenceVersionsTableOrderingComposer(
                $db: db,
                $table: table,
              ),
          createComputedFieldComposer: () =>
              $$PinnedReferenceVersionsTableAnnotationComposer(
                $db: db,
                $table: table,
              ),
          updateCompanionCallback:
              ({
                Value<String> listCode = const Value.absent(),
                Value<int> pinnedVersion = const Value.absent(),
                Value<DateTime> pinnedAt = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => PinnedReferenceVersionsCompanion(
                listCode: listCode,
                pinnedVersion: pinnedVersion,
                pinnedAt: pinnedAt,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String listCode,
                required int pinnedVersion,
                required DateTime pinnedAt,
                Value<int> rowid = const Value.absent(),
              }) => PinnedReferenceVersionsCompanion.insert(
                listCode: listCode,
                pinnedVersion: pinnedVersion,
                pinnedAt: pinnedAt,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$PinnedReferenceVersionsTableProcessedTableManager =
    ProcessedTableManager<
      _$SessionDatabase,
      $PinnedReferenceVersionsTable,
      PinnedReferenceVersion,
      $$PinnedReferenceVersionsTableFilterComposer,
      $$PinnedReferenceVersionsTableOrderingComposer,
      $$PinnedReferenceVersionsTableAnnotationComposer,
      $$PinnedReferenceVersionsTableCreateCompanionBuilder,
      $$PinnedReferenceVersionsTableUpdateCompanionBuilder,
      (
        PinnedReferenceVersion,
        BaseReferences<
          _$SessionDatabase,
          $PinnedReferenceVersionsTable,
          PinnedReferenceVersion
        >,
      ),
      PinnedReferenceVersion,
      PrefetchHooks Function()
    >;
typedef $$LocalDraftTableCreateCompanionBuilder =
    LocalDraftCompanion Function({
      Value<int> id,
      Value<String?> branchCode,
      Value<String?> accountNumber,
      Value<String?> phoneNumber,
      Value<bool> smsSelected,
      Value<bool> whatsappSelected,
      Value<String?> emailAddress,
      Value<bool> emailSelected,
      required DateTime updatedAt,
    });
typedef $$LocalDraftTableUpdateCompanionBuilder =
    LocalDraftCompanion Function({
      Value<int> id,
      Value<String?> branchCode,
      Value<String?> accountNumber,
      Value<String?> phoneNumber,
      Value<bool> smsSelected,
      Value<bool> whatsappSelected,
      Value<String?> emailAddress,
      Value<bool> emailSelected,
      Value<DateTime> updatedAt,
    });

class $$LocalDraftTableFilterComposer
    extends Composer<_$SessionDatabase, $LocalDraftTable> {
  $$LocalDraftTableFilterComposer({
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

  ColumnFilters<String> get branchCode => $composableBuilder(
    column: $table.branchCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get accountNumber => $composableBuilder(
    column: $table.accountNumber,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get phoneNumber => $composableBuilder(
    column: $table.phoneNumber,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get smsSelected => $composableBuilder(
    column: $table.smsSelected,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get whatsappSelected => $composableBuilder(
    column: $table.whatsappSelected,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get emailAddress => $composableBuilder(
    column: $table.emailAddress,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get emailSelected => $composableBuilder(
    column: $table.emailSelected,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get updatedAt => $composableBuilder(
    column: $table.updatedAt,
    builder: (column) => ColumnFilters(column),
  );
}

class $$LocalDraftTableOrderingComposer
    extends Composer<_$SessionDatabase, $LocalDraftTable> {
  $$LocalDraftTableOrderingComposer({
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

  ColumnOrderings<String> get branchCode => $composableBuilder(
    column: $table.branchCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get accountNumber => $composableBuilder(
    column: $table.accountNumber,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get phoneNumber => $composableBuilder(
    column: $table.phoneNumber,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get smsSelected => $composableBuilder(
    column: $table.smsSelected,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get whatsappSelected => $composableBuilder(
    column: $table.whatsappSelected,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get emailAddress => $composableBuilder(
    column: $table.emailAddress,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get emailSelected => $composableBuilder(
    column: $table.emailSelected,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get updatedAt => $composableBuilder(
    column: $table.updatedAt,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$LocalDraftTableAnnotationComposer
    extends Composer<_$SessionDatabase, $LocalDraftTable> {
  $$LocalDraftTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<int> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get branchCode => $composableBuilder(
    column: $table.branchCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get accountNumber => $composableBuilder(
    column: $table.accountNumber,
    builder: (column) => column,
  );

  GeneratedColumn<String> get phoneNumber => $composableBuilder(
    column: $table.phoneNumber,
    builder: (column) => column,
  );

  GeneratedColumn<bool> get smsSelected => $composableBuilder(
    column: $table.smsSelected,
    builder: (column) => column,
  );

  GeneratedColumn<bool> get whatsappSelected => $composableBuilder(
    column: $table.whatsappSelected,
    builder: (column) => column,
  );

  GeneratedColumn<String> get emailAddress => $composableBuilder(
    column: $table.emailAddress,
    builder: (column) => column,
  );

  GeneratedColumn<bool> get emailSelected => $composableBuilder(
    column: $table.emailSelected,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get updatedAt =>
      $composableBuilder(column: $table.updatedAt, builder: (column) => column);
}

class $$LocalDraftTableTableManager
    extends
        RootTableManager<
          _$SessionDatabase,
          $LocalDraftTable,
          LocalDraftData,
          $$LocalDraftTableFilterComposer,
          $$LocalDraftTableOrderingComposer,
          $$LocalDraftTableAnnotationComposer,
          $$LocalDraftTableCreateCompanionBuilder,
          $$LocalDraftTableUpdateCompanionBuilder,
          (
            LocalDraftData,
            BaseReferences<_$SessionDatabase, $LocalDraftTable, LocalDraftData>,
          ),
          LocalDraftData,
          PrefetchHooks Function()
        > {
  $$LocalDraftTableTableManager(_$SessionDatabase db, $LocalDraftTable table)
    : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$LocalDraftTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$LocalDraftTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$LocalDraftTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                Value<String?> branchCode = const Value.absent(),
                Value<String?> accountNumber = const Value.absent(),
                Value<String?> phoneNumber = const Value.absent(),
                Value<bool> smsSelected = const Value.absent(),
                Value<bool> whatsappSelected = const Value.absent(),
                Value<String?> emailAddress = const Value.absent(),
                Value<bool> emailSelected = const Value.absent(),
                Value<DateTime> updatedAt = const Value.absent(),
              }) => LocalDraftCompanion(
                id: id,
                branchCode: branchCode,
                accountNumber: accountNumber,
                phoneNumber: phoneNumber,
                smsSelected: smsSelected,
                whatsappSelected: whatsappSelected,
                emailAddress: emailAddress,
                emailSelected: emailSelected,
                updatedAt: updatedAt,
              ),
          createCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                Value<String?> branchCode = const Value.absent(),
                Value<String?> accountNumber = const Value.absent(),
                Value<String?> phoneNumber = const Value.absent(),
                Value<bool> smsSelected = const Value.absent(),
                Value<bool> whatsappSelected = const Value.absent(),
                Value<String?> emailAddress = const Value.absent(),
                Value<bool> emailSelected = const Value.absent(),
                required DateTime updatedAt,
              }) => LocalDraftCompanion.insert(
                id: id,
                branchCode: branchCode,
                accountNumber: accountNumber,
                phoneNumber: phoneNumber,
                smsSelected: smsSelected,
                whatsappSelected: whatsappSelected,
                emailAddress: emailAddress,
                emailSelected: emailSelected,
                updatedAt: updatedAt,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$LocalDraftTableProcessedTableManager =
    ProcessedTableManager<
      _$SessionDatabase,
      $LocalDraftTable,
      LocalDraftData,
      $$LocalDraftTableFilterComposer,
      $$LocalDraftTableOrderingComposer,
      $$LocalDraftTableAnnotationComposer,
      $$LocalDraftTableCreateCompanionBuilder,
      $$LocalDraftTableUpdateCompanionBuilder,
      (
        LocalDraftData,
        BaseReferences<_$SessionDatabase, $LocalDraftTable, LocalDraftData>,
      ),
      LocalDraftData,
      PrefetchHooks Function()
    >;
typedef $$LocalProgressTableCreateCompanionBuilder =
    LocalProgressCompanion Function({
      Value<int> id,
      required String verifiedBranchCode,
      required String verifiedAccountNumber,
      required String resumeStage,
      Value<String?> profileId,
      Value<String?> channelsSummary,
      required DateTime updatedAt,
    });
typedef $$LocalProgressTableUpdateCompanionBuilder =
    LocalProgressCompanion Function({
      Value<int> id,
      Value<String> verifiedBranchCode,
      Value<String> verifiedAccountNumber,
      Value<String> resumeStage,
      Value<String?> profileId,
      Value<String?> channelsSummary,
      Value<DateTime> updatedAt,
    });

class $$LocalProgressTableFilterComposer
    extends Composer<_$SessionDatabase, $LocalProgressTable> {
  $$LocalProgressTableFilterComposer({
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

  ColumnFilters<String> get verifiedBranchCode => $composableBuilder(
    column: $table.verifiedBranchCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get verifiedAccountNumber => $composableBuilder(
    column: $table.verifiedAccountNumber,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get resumeStage => $composableBuilder(
    column: $table.resumeStage,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get profileId => $composableBuilder(
    column: $table.profileId,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get channelsSummary => $composableBuilder(
    column: $table.channelsSummary,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get updatedAt => $composableBuilder(
    column: $table.updatedAt,
    builder: (column) => ColumnFilters(column),
  );
}

class $$LocalProgressTableOrderingComposer
    extends Composer<_$SessionDatabase, $LocalProgressTable> {
  $$LocalProgressTableOrderingComposer({
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

  ColumnOrderings<String> get verifiedBranchCode => $composableBuilder(
    column: $table.verifiedBranchCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get verifiedAccountNumber => $composableBuilder(
    column: $table.verifiedAccountNumber,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get resumeStage => $composableBuilder(
    column: $table.resumeStage,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get profileId => $composableBuilder(
    column: $table.profileId,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get channelsSummary => $composableBuilder(
    column: $table.channelsSummary,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get updatedAt => $composableBuilder(
    column: $table.updatedAt,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$LocalProgressTableAnnotationComposer
    extends Composer<_$SessionDatabase, $LocalProgressTable> {
  $$LocalProgressTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<int> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get verifiedBranchCode => $composableBuilder(
    column: $table.verifiedBranchCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get verifiedAccountNumber => $composableBuilder(
    column: $table.verifiedAccountNumber,
    builder: (column) => column,
  );

  GeneratedColumn<String> get resumeStage => $composableBuilder(
    column: $table.resumeStage,
    builder: (column) => column,
  );

  GeneratedColumn<String> get profileId =>
      $composableBuilder(column: $table.profileId, builder: (column) => column);

  GeneratedColumn<String> get channelsSummary => $composableBuilder(
    column: $table.channelsSummary,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get updatedAt =>
      $composableBuilder(column: $table.updatedAt, builder: (column) => column);
}

class $$LocalProgressTableTableManager
    extends
        RootTableManager<
          _$SessionDatabase,
          $LocalProgressTable,
          LocalProgressData,
          $$LocalProgressTableFilterComposer,
          $$LocalProgressTableOrderingComposer,
          $$LocalProgressTableAnnotationComposer,
          $$LocalProgressTableCreateCompanionBuilder,
          $$LocalProgressTableUpdateCompanionBuilder,
          (
            LocalProgressData,
            BaseReferences<
              _$SessionDatabase,
              $LocalProgressTable,
              LocalProgressData
            >,
          ),
          LocalProgressData,
          PrefetchHooks Function()
        > {
  $$LocalProgressTableTableManager(
    _$SessionDatabase db,
    $LocalProgressTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$LocalProgressTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$LocalProgressTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$LocalProgressTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                Value<String> verifiedBranchCode = const Value.absent(),
                Value<String> verifiedAccountNumber = const Value.absent(),
                Value<String> resumeStage = const Value.absent(),
                Value<String?> profileId = const Value.absent(),
                Value<String?> channelsSummary = const Value.absent(),
                Value<DateTime> updatedAt = const Value.absent(),
              }) => LocalProgressCompanion(
                id: id,
                verifiedBranchCode: verifiedBranchCode,
                verifiedAccountNumber: verifiedAccountNumber,
                resumeStage: resumeStage,
                profileId: profileId,
                channelsSummary: channelsSummary,
                updatedAt: updatedAt,
              ),
          createCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                required String verifiedBranchCode,
                required String verifiedAccountNumber,
                required String resumeStage,
                Value<String?> profileId = const Value.absent(),
                Value<String?> channelsSummary = const Value.absent(),
                required DateTime updatedAt,
              }) => LocalProgressCompanion.insert(
                id: id,
                verifiedBranchCode: verifiedBranchCode,
                verifiedAccountNumber: verifiedAccountNumber,
                resumeStage: resumeStage,
                profileId: profileId,
                channelsSummary: channelsSummary,
                updatedAt: updatedAt,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$LocalProgressTableProcessedTableManager =
    ProcessedTableManager<
      _$SessionDatabase,
      $LocalProgressTable,
      LocalProgressData,
      $$LocalProgressTableFilterComposer,
      $$LocalProgressTableOrderingComposer,
      $$LocalProgressTableAnnotationComposer,
      $$LocalProgressTableCreateCompanionBuilder,
      $$LocalProgressTableUpdateCompanionBuilder,
      (
        LocalProgressData,
        BaseReferences<
          _$SessionDatabase,
          $LocalProgressTable,
          LocalProgressData
        >,
      ),
      LocalProgressData,
      PrefetchHooks Function()
    >;
typedef $$DataEntryDraftTableCreateCompanionBuilder =
    DataEntryDraftCompanion Function({
      Value<int> id,
      Value<String?> sexDeclared,
      Value<String?> ethnicity,
      Value<String?> countryOfResidenceCode,
      Value<String?> maritalStatus,
      Value<String?> spouseName,
      Value<bool?> hasChildren,
      Value<int?> childrenCount,
      Value<int?> educationLevel,
      Value<String?> birthCountryCode,
      Value<String?> birthStateCode,
      Value<String?> birthStateText,
      Value<String?> birthCityText,
      Value<String?> occupationCode,
      Value<String?> monthlyExpensesSdg,
      Value<String?> homeCountryCode,
      Value<String?> homeStateCode,
      Value<String?> homeStateText,
      Value<String?> homeLocalityCode,
      Value<String?> homeLocalityText,
      Value<String?> homeCity,
      Value<String?> homeArea,
      Value<String?> homeStreet,
      Value<String?> homeBlock,
      Value<String?> homeHouseNumber,
      Value<String?> workEmployer,
      Value<String?> workCountryCode,
      Value<String?> workStateCode,
      Value<String?> workStateText,
      Value<String?> workLocalityCode,
      Value<String?> workLocalityText,
      Value<String?> workCity,
      Value<String?> workArea,
      Value<String?> workStreet,
      Value<String?> workBlock,
      Value<String?> salaryCertificatePath,
      Value<DateTime?> salaryCertificateUploadedAt,
      Value<String?> identityType,
      required DateTime updatedAt,
    });
typedef $$DataEntryDraftTableUpdateCompanionBuilder =
    DataEntryDraftCompanion Function({
      Value<int> id,
      Value<String?> sexDeclared,
      Value<String?> ethnicity,
      Value<String?> countryOfResidenceCode,
      Value<String?> maritalStatus,
      Value<String?> spouseName,
      Value<bool?> hasChildren,
      Value<int?> childrenCount,
      Value<int?> educationLevel,
      Value<String?> birthCountryCode,
      Value<String?> birthStateCode,
      Value<String?> birthStateText,
      Value<String?> birthCityText,
      Value<String?> occupationCode,
      Value<String?> monthlyExpensesSdg,
      Value<String?> homeCountryCode,
      Value<String?> homeStateCode,
      Value<String?> homeStateText,
      Value<String?> homeLocalityCode,
      Value<String?> homeLocalityText,
      Value<String?> homeCity,
      Value<String?> homeArea,
      Value<String?> homeStreet,
      Value<String?> homeBlock,
      Value<String?> homeHouseNumber,
      Value<String?> workEmployer,
      Value<String?> workCountryCode,
      Value<String?> workStateCode,
      Value<String?> workStateText,
      Value<String?> workLocalityCode,
      Value<String?> workLocalityText,
      Value<String?> workCity,
      Value<String?> workArea,
      Value<String?> workStreet,
      Value<String?> workBlock,
      Value<String?> salaryCertificatePath,
      Value<DateTime?> salaryCertificateUploadedAt,
      Value<String?> identityType,
      Value<DateTime> updatedAt,
    });

class $$DataEntryDraftTableFilterComposer
    extends Composer<_$SessionDatabase, $DataEntryDraftTable> {
  $$DataEntryDraftTableFilterComposer({
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

  ColumnFilters<String> get sexDeclared => $composableBuilder(
    column: $table.sexDeclared,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get ethnicity => $composableBuilder(
    column: $table.ethnicity,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get countryOfResidenceCode => $composableBuilder(
    column: $table.countryOfResidenceCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get maritalStatus => $composableBuilder(
    column: $table.maritalStatus,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get spouseName => $composableBuilder(
    column: $table.spouseName,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get hasChildren => $composableBuilder(
    column: $table.hasChildren,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get childrenCount => $composableBuilder(
    column: $table.childrenCount,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<int> get educationLevel => $composableBuilder(
    column: $table.educationLevel,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get birthCountryCode => $composableBuilder(
    column: $table.birthCountryCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get birthStateCode => $composableBuilder(
    column: $table.birthStateCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get birthStateText => $composableBuilder(
    column: $table.birthStateText,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get birthCityText => $composableBuilder(
    column: $table.birthCityText,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get occupationCode => $composableBuilder(
    column: $table.occupationCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get monthlyExpensesSdg => $composableBuilder(
    column: $table.monthlyExpensesSdg,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeCountryCode => $composableBuilder(
    column: $table.homeCountryCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeStateCode => $composableBuilder(
    column: $table.homeStateCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeStateText => $composableBuilder(
    column: $table.homeStateText,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeLocalityCode => $composableBuilder(
    column: $table.homeLocalityCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeLocalityText => $composableBuilder(
    column: $table.homeLocalityText,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeCity => $composableBuilder(
    column: $table.homeCity,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeArea => $composableBuilder(
    column: $table.homeArea,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeStreet => $composableBuilder(
    column: $table.homeStreet,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeBlock => $composableBuilder(
    column: $table.homeBlock,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get homeHouseNumber => $composableBuilder(
    column: $table.homeHouseNumber,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workEmployer => $composableBuilder(
    column: $table.workEmployer,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workCountryCode => $composableBuilder(
    column: $table.workCountryCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workStateCode => $composableBuilder(
    column: $table.workStateCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workStateText => $composableBuilder(
    column: $table.workStateText,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workLocalityCode => $composableBuilder(
    column: $table.workLocalityCode,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workLocalityText => $composableBuilder(
    column: $table.workLocalityText,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workCity => $composableBuilder(
    column: $table.workCity,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workArea => $composableBuilder(
    column: $table.workArea,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workStreet => $composableBuilder(
    column: $table.workStreet,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get workBlock => $composableBuilder(
    column: $table.workBlock,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get salaryCertificatePath => $composableBuilder(
    column: $table.salaryCertificatePath,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get salaryCertificateUploadedAt => $composableBuilder(
    column: $table.salaryCertificateUploadedAt,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get identityType => $composableBuilder(
    column: $table.identityType,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get updatedAt => $composableBuilder(
    column: $table.updatedAt,
    builder: (column) => ColumnFilters(column),
  );
}

class $$DataEntryDraftTableOrderingComposer
    extends Composer<_$SessionDatabase, $DataEntryDraftTable> {
  $$DataEntryDraftTableOrderingComposer({
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

  ColumnOrderings<String> get sexDeclared => $composableBuilder(
    column: $table.sexDeclared,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get ethnicity => $composableBuilder(
    column: $table.ethnicity,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get countryOfResidenceCode => $composableBuilder(
    column: $table.countryOfResidenceCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get maritalStatus => $composableBuilder(
    column: $table.maritalStatus,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get spouseName => $composableBuilder(
    column: $table.spouseName,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get hasChildren => $composableBuilder(
    column: $table.hasChildren,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get childrenCount => $composableBuilder(
    column: $table.childrenCount,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<int> get educationLevel => $composableBuilder(
    column: $table.educationLevel,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get birthCountryCode => $composableBuilder(
    column: $table.birthCountryCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get birthStateCode => $composableBuilder(
    column: $table.birthStateCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get birthStateText => $composableBuilder(
    column: $table.birthStateText,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get birthCityText => $composableBuilder(
    column: $table.birthCityText,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get occupationCode => $composableBuilder(
    column: $table.occupationCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get monthlyExpensesSdg => $composableBuilder(
    column: $table.monthlyExpensesSdg,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeCountryCode => $composableBuilder(
    column: $table.homeCountryCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeStateCode => $composableBuilder(
    column: $table.homeStateCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeStateText => $composableBuilder(
    column: $table.homeStateText,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeLocalityCode => $composableBuilder(
    column: $table.homeLocalityCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeLocalityText => $composableBuilder(
    column: $table.homeLocalityText,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeCity => $composableBuilder(
    column: $table.homeCity,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeArea => $composableBuilder(
    column: $table.homeArea,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeStreet => $composableBuilder(
    column: $table.homeStreet,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeBlock => $composableBuilder(
    column: $table.homeBlock,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get homeHouseNumber => $composableBuilder(
    column: $table.homeHouseNumber,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workEmployer => $composableBuilder(
    column: $table.workEmployer,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workCountryCode => $composableBuilder(
    column: $table.workCountryCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workStateCode => $composableBuilder(
    column: $table.workStateCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workStateText => $composableBuilder(
    column: $table.workStateText,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workLocalityCode => $composableBuilder(
    column: $table.workLocalityCode,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workLocalityText => $composableBuilder(
    column: $table.workLocalityText,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workCity => $composableBuilder(
    column: $table.workCity,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workArea => $composableBuilder(
    column: $table.workArea,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workStreet => $composableBuilder(
    column: $table.workStreet,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get workBlock => $composableBuilder(
    column: $table.workBlock,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get salaryCertificatePath => $composableBuilder(
    column: $table.salaryCertificatePath,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get salaryCertificateUploadedAt =>
      $composableBuilder(
        column: $table.salaryCertificateUploadedAt,
        builder: (column) => ColumnOrderings(column),
      );

  ColumnOrderings<String> get identityType => $composableBuilder(
    column: $table.identityType,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get updatedAt => $composableBuilder(
    column: $table.updatedAt,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$DataEntryDraftTableAnnotationComposer
    extends Composer<_$SessionDatabase, $DataEntryDraftTable> {
  $$DataEntryDraftTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<int> get id =>
      $composableBuilder(column: $table.id, builder: (column) => column);

  GeneratedColumn<String> get sexDeclared => $composableBuilder(
    column: $table.sexDeclared,
    builder: (column) => column,
  );

  GeneratedColumn<String> get ethnicity =>
      $composableBuilder(column: $table.ethnicity, builder: (column) => column);

  GeneratedColumn<String> get countryOfResidenceCode => $composableBuilder(
    column: $table.countryOfResidenceCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get maritalStatus => $composableBuilder(
    column: $table.maritalStatus,
    builder: (column) => column,
  );

  GeneratedColumn<String> get spouseName => $composableBuilder(
    column: $table.spouseName,
    builder: (column) => column,
  );

  GeneratedColumn<bool> get hasChildren => $composableBuilder(
    column: $table.hasChildren,
    builder: (column) => column,
  );

  GeneratedColumn<int> get childrenCount => $composableBuilder(
    column: $table.childrenCount,
    builder: (column) => column,
  );

  GeneratedColumn<int> get educationLevel => $composableBuilder(
    column: $table.educationLevel,
    builder: (column) => column,
  );

  GeneratedColumn<String> get birthCountryCode => $composableBuilder(
    column: $table.birthCountryCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get birthStateCode => $composableBuilder(
    column: $table.birthStateCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get birthStateText => $composableBuilder(
    column: $table.birthStateText,
    builder: (column) => column,
  );

  GeneratedColumn<String> get birthCityText => $composableBuilder(
    column: $table.birthCityText,
    builder: (column) => column,
  );

  GeneratedColumn<String> get occupationCode => $composableBuilder(
    column: $table.occupationCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get monthlyExpensesSdg => $composableBuilder(
    column: $table.monthlyExpensesSdg,
    builder: (column) => column,
  );

  GeneratedColumn<String> get homeCountryCode => $composableBuilder(
    column: $table.homeCountryCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get homeStateCode => $composableBuilder(
    column: $table.homeStateCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get homeStateText => $composableBuilder(
    column: $table.homeStateText,
    builder: (column) => column,
  );

  GeneratedColumn<String> get homeLocalityCode => $composableBuilder(
    column: $table.homeLocalityCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get homeLocalityText => $composableBuilder(
    column: $table.homeLocalityText,
    builder: (column) => column,
  );

  GeneratedColumn<String> get homeCity =>
      $composableBuilder(column: $table.homeCity, builder: (column) => column);

  GeneratedColumn<String> get homeArea =>
      $composableBuilder(column: $table.homeArea, builder: (column) => column);

  GeneratedColumn<String> get homeStreet => $composableBuilder(
    column: $table.homeStreet,
    builder: (column) => column,
  );

  GeneratedColumn<String> get homeBlock =>
      $composableBuilder(column: $table.homeBlock, builder: (column) => column);

  GeneratedColumn<String> get homeHouseNumber => $composableBuilder(
    column: $table.homeHouseNumber,
    builder: (column) => column,
  );

  GeneratedColumn<String> get workEmployer => $composableBuilder(
    column: $table.workEmployer,
    builder: (column) => column,
  );

  GeneratedColumn<String> get workCountryCode => $composableBuilder(
    column: $table.workCountryCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get workStateCode => $composableBuilder(
    column: $table.workStateCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get workStateText => $composableBuilder(
    column: $table.workStateText,
    builder: (column) => column,
  );

  GeneratedColumn<String> get workLocalityCode => $composableBuilder(
    column: $table.workLocalityCode,
    builder: (column) => column,
  );

  GeneratedColumn<String> get workLocalityText => $composableBuilder(
    column: $table.workLocalityText,
    builder: (column) => column,
  );

  GeneratedColumn<String> get workCity =>
      $composableBuilder(column: $table.workCity, builder: (column) => column);

  GeneratedColumn<String> get workArea =>
      $composableBuilder(column: $table.workArea, builder: (column) => column);

  GeneratedColumn<String> get workStreet => $composableBuilder(
    column: $table.workStreet,
    builder: (column) => column,
  );

  GeneratedColumn<String> get workBlock =>
      $composableBuilder(column: $table.workBlock, builder: (column) => column);

  GeneratedColumn<String> get salaryCertificatePath => $composableBuilder(
    column: $table.salaryCertificatePath,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get salaryCertificateUploadedAt =>
      $composableBuilder(
        column: $table.salaryCertificateUploadedAt,
        builder: (column) => column,
      );

  GeneratedColumn<String> get identityType => $composableBuilder(
    column: $table.identityType,
    builder: (column) => column,
  );

  GeneratedColumn<DateTime> get updatedAt =>
      $composableBuilder(column: $table.updatedAt, builder: (column) => column);
}

class $$DataEntryDraftTableTableManager
    extends
        RootTableManager<
          _$SessionDatabase,
          $DataEntryDraftTable,
          DataEntryDraftData,
          $$DataEntryDraftTableFilterComposer,
          $$DataEntryDraftTableOrderingComposer,
          $$DataEntryDraftTableAnnotationComposer,
          $$DataEntryDraftTableCreateCompanionBuilder,
          $$DataEntryDraftTableUpdateCompanionBuilder,
          (
            DataEntryDraftData,
            BaseReferences<
              _$SessionDatabase,
              $DataEntryDraftTable,
              DataEntryDraftData
            >,
          ),
          DataEntryDraftData,
          PrefetchHooks Function()
        > {
  $$DataEntryDraftTableTableManager(
    _$SessionDatabase db,
    $DataEntryDraftTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$DataEntryDraftTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$DataEntryDraftTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$DataEntryDraftTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                Value<String?> sexDeclared = const Value.absent(),
                Value<String?> ethnicity = const Value.absent(),
                Value<String?> countryOfResidenceCode = const Value.absent(),
                Value<String?> maritalStatus = const Value.absent(),
                Value<String?> spouseName = const Value.absent(),
                Value<bool?> hasChildren = const Value.absent(),
                Value<int?> childrenCount = const Value.absent(),
                Value<int?> educationLevel = const Value.absent(),
                Value<String?> birthCountryCode = const Value.absent(),
                Value<String?> birthStateCode = const Value.absent(),
                Value<String?> birthStateText = const Value.absent(),
                Value<String?> birthCityText = const Value.absent(),
                Value<String?> occupationCode = const Value.absent(),
                Value<String?> monthlyExpensesSdg = const Value.absent(),
                Value<String?> homeCountryCode = const Value.absent(),
                Value<String?> homeStateCode = const Value.absent(),
                Value<String?> homeStateText = const Value.absent(),
                Value<String?> homeLocalityCode = const Value.absent(),
                Value<String?> homeLocalityText = const Value.absent(),
                Value<String?> homeCity = const Value.absent(),
                Value<String?> homeArea = const Value.absent(),
                Value<String?> homeStreet = const Value.absent(),
                Value<String?> homeBlock = const Value.absent(),
                Value<String?> homeHouseNumber = const Value.absent(),
                Value<String?> workEmployer = const Value.absent(),
                Value<String?> workCountryCode = const Value.absent(),
                Value<String?> workStateCode = const Value.absent(),
                Value<String?> workStateText = const Value.absent(),
                Value<String?> workLocalityCode = const Value.absent(),
                Value<String?> workLocalityText = const Value.absent(),
                Value<String?> workCity = const Value.absent(),
                Value<String?> workArea = const Value.absent(),
                Value<String?> workStreet = const Value.absent(),
                Value<String?> workBlock = const Value.absent(),
                Value<String?> salaryCertificatePath = const Value.absent(),
                Value<DateTime?> salaryCertificateUploadedAt =
                    const Value.absent(),
                Value<String?> identityType = const Value.absent(),
                Value<DateTime> updatedAt = const Value.absent(),
              }) => DataEntryDraftCompanion(
                id: id,
                sexDeclared: sexDeclared,
                ethnicity: ethnicity,
                countryOfResidenceCode: countryOfResidenceCode,
                maritalStatus: maritalStatus,
                spouseName: spouseName,
                hasChildren: hasChildren,
                childrenCount: childrenCount,
                educationLevel: educationLevel,
                birthCountryCode: birthCountryCode,
                birthStateCode: birthStateCode,
                birthStateText: birthStateText,
                birthCityText: birthCityText,
                occupationCode: occupationCode,
                monthlyExpensesSdg: monthlyExpensesSdg,
                homeCountryCode: homeCountryCode,
                homeStateCode: homeStateCode,
                homeStateText: homeStateText,
                homeLocalityCode: homeLocalityCode,
                homeLocalityText: homeLocalityText,
                homeCity: homeCity,
                homeArea: homeArea,
                homeStreet: homeStreet,
                homeBlock: homeBlock,
                homeHouseNumber: homeHouseNumber,
                workEmployer: workEmployer,
                workCountryCode: workCountryCode,
                workStateCode: workStateCode,
                workStateText: workStateText,
                workLocalityCode: workLocalityCode,
                workLocalityText: workLocalityText,
                workCity: workCity,
                workArea: workArea,
                workStreet: workStreet,
                workBlock: workBlock,
                salaryCertificatePath: salaryCertificatePath,
                salaryCertificateUploadedAt: salaryCertificateUploadedAt,
                identityType: identityType,
                updatedAt: updatedAt,
              ),
          createCompanionCallback:
              ({
                Value<int> id = const Value.absent(),
                Value<String?> sexDeclared = const Value.absent(),
                Value<String?> ethnicity = const Value.absent(),
                Value<String?> countryOfResidenceCode = const Value.absent(),
                Value<String?> maritalStatus = const Value.absent(),
                Value<String?> spouseName = const Value.absent(),
                Value<bool?> hasChildren = const Value.absent(),
                Value<int?> childrenCount = const Value.absent(),
                Value<int?> educationLevel = const Value.absent(),
                Value<String?> birthCountryCode = const Value.absent(),
                Value<String?> birthStateCode = const Value.absent(),
                Value<String?> birthStateText = const Value.absent(),
                Value<String?> birthCityText = const Value.absent(),
                Value<String?> occupationCode = const Value.absent(),
                Value<String?> monthlyExpensesSdg = const Value.absent(),
                Value<String?> homeCountryCode = const Value.absent(),
                Value<String?> homeStateCode = const Value.absent(),
                Value<String?> homeStateText = const Value.absent(),
                Value<String?> homeLocalityCode = const Value.absent(),
                Value<String?> homeLocalityText = const Value.absent(),
                Value<String?> homeCity = const Value.absent(),
                Value<String?> homeArea = const Value.absent(),
                Value<String?> homeStreet = const Value.absent(),
                Value<String?> homeBlock = const Value.absent(),
                Value<String?> homeHouseNumber = const Value.absent(),
                Value<String?> workEmployer = const Value.absent(),
                Value<String?> workCountryCode = const Value.absent(),
                Value<String?> workStateCode = const Value.absent(),
                Value<String?> workStateText = const Value.absent(),
                Value<String?> workLocalityCode = const Value.absent(),
                Value<String?> workLocalityText = const Value.absent(),
                Value<String?> workCity = const Value.absent(),
                Value<String?> workArea = const Value.absent(),
                Value<String?> workStreet = const Value.absent(),
                Value<String?> workBlock = const Value.absent(),
                Value<String?> salaryCertificatePath = const Value.absent(),
                Value<DateTime?> salaryCertificateUploadedAt =
                    const Value.absent(),
                Value<String?> identityType = const Value.absent(),
                required DateTime updatedAt,
              }) => DataEntryDraftCompanion.insert(
                id: id,
                sexDeclared: sexDeclared,
                ethnicity: ethnicity,
                countryOfResidenceCode: countryOfResidenceCode,
                maritalStatus: maritalStatus,
                spouseName: spouseName,
                hasChildren: hasChildren,
                childrenCount: childrenCount,
                educationLevel: educationLevel,
                birthCountryCode: birthCountryCode,
                birthStateCode: birthStateCode,
                birthStateText: birthStateText,
                birthCityText: birthCityText,
                occupationCode: occupationCode,
                monthlyExpensesSdg: monthlyExpensesSdg,
                homeCountryCode: homeCountryCode,
                homeStateCode: homeStateCode,
                homeStateText: homeStateText,
                homeLocalityCode: homeLocalityCode,
                homeLocalityText: homeLocalityText,
                homeCity: homeCity,
                homeArea: homeArea,
                homeStreet: homeStreet,
                homeBlock: homeBlock,
                homeHouseNumber: homeHouseNumber,
                workEmployer: workEmployer,
                workCountryCode: workCountryCode,
                workStateCode: workStateCode,
                workStateText: workStateText,
                workLocalityCode: workLocalityCode,
                workLocalityText: workLocalityText,
                workCity: workCity,
                workArea: workArea,
                workStreet: workStreet,
                workBlock: workBlock,
                salaryCertificatePath: salaryCertificatePath,
                salaryCertificateUploadedAt: salaryCertificateUploadedAt,
                identityType: identityType,
                updatedAt: updatedAt,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$DataEntryDraftTableProcessedTableManager =
    ProcessedTableManager<
      _$SessionDatabase,
      $DataEntryDraftTable,
      DataEntryDraftData,
      $$DataEntryDraftTableFilterComposer,
      $$DataEntryDraftTableOrderingComposer,
      $$DataEntryDraftTableAnnotationComposer,
      $$DataEntryDraftTableCreateCompanionBuilder,
      $$DataEntryDraftTableUpdateCompanionBuilder,
      (
        DataEntryDraftData,
        BaseReferences<
          _$SessionDatabase,
          $DataEntryDraftTable,
          DataEntryDraftData
        >,
      ),
      DataEntryDraftData,
      PrefetchHooks Function()
    >;
typedef $$DataEntryIncomeSourcesTableCreateCompanionBuilder =
    DataEntryIncomeSourcesCompanion Function({
      required String code,
      Value<bool> isPrimary,
      Value<String?> otherText,
      Value<int> rowid,
    });
typedef $$DataEntryIncomeSourcesTableUpdateCompanionBuilder =
    DataEntryIncomeSourcesCompanion Function({
      Value<String> code,
      Value<bool> isPrimary,
      Value<String?> otherText,
      Value<int> rowid,
    });

class $$DataEntryIncomeSourcesTableFilterComposer
    extends Composer<_$SessionDatabase, $DataEntryIncomeSourcesTable> {
  $$DataEntryIncomeSourcesTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get code => $composableBuilder(
    column: $table.code,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<bool> get isPrimary => $composableBuilder(
    column: $table.isPrimary,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<String> get otherText => $composableBuilder(
    column: $table.otherText,
    builder: (column) => ColumnFilters(column),
  );
}

class $$DataEntryIncomeSourcesTableOrderingComposer
    extends Composer<_$SessionDatabase, $DataEntryIncomeSourcesTable> {
  $$DataEntryIncomeSourcesTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get code => $composableBuilder(
    column: $table.code,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<bool> get isPrimary => $composableBuilder(
    column: $table.isPrimary,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<String> get otherText => $composableBuilder(
    column: $table.otherText,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$DataEntryIncomeSourcesTableAnnotationComposer
    extends Composer<_$SessionDatabase, $DataEntryIncomeSourcesTable> {
  $$DataEntryIncomeSourcesTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get code =>
      $composableBuilder(column: $table.code, builder: (column) => column);

  GeneratedColumn<bool> get isPrimary =>
      $composableBuilder(column: $table.isPrimary, builder: (column) => column);

  GeneratedColumn<String> get otherText =>
      $composableBuilder(column: $table.otherText, builder: (column) => column);
}

class $$DataEntryIncomeSourcesTableTableManager
    extends
        RootTableManager<
          _$SessionDatabase,
          $DataEntryIncomeSourcesTable,
          DataEntryIncomeSource,
          $$DataEntryIncomeSourcesTableFilterComposer,
          $$DataEntryIncomeSourcesTableOrderingComposer,
          $$DataEntryIncomeSourcesTableAnnotationComposer,
          $$DataEntryIncomeSourcesTableCreateCompanionBuilder,
          $$DataEntryIncomeSourcesTableUpdateCompanionBuilder,
          (
            DataEntryIncomeSource,
            BaseReferences<
              _$SessionDatabase,
              $DataEntryIncomeSourcesTable,
              DataEntryIncomeSource
            >,
          ),
          DataEntryIncomeSource,
          PrefetchHooks Function()
        > {
  $$DataEntryIncomeSourcesTableTableManager(
    _$SessionDatabase db,
    $DataEntryIncomeSourcesTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$DataEntryIncomeSourcesTableFilterComposer(
                $db: db,
                $table: table,
              ),
          createOrderingComposer: () =>
              $$DataEntryIncomeSourcesTableOrderingComposer(
                $db: db,
                $table: table,
              ),
          createComputedFieldComposer: () =>
              $$DataEntryIncomeSourcesTableAnnotationComposer(
                $db: db,
                $table: table,
              ),
          updateCompanionCallback:
              ({
                Value<String> code = const Value.absent(),
                Value<bool> isPrimary = const Value.absent(),
                Value<String?> otherText = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => DataEntryIncomeSourcesCompanion(
                code: code,
                isPrimary: isPrimary,
                otherText: otherText,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String code,
                Value<bool> isPrimary = const Value.absent(),
                Value<String?> otherText = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => DataEntryIncomeSourcesCompanion.insert(
                code: code,
                isPrimary: isPrimary,
                otherText: otherText,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$DataEntryIncomeSourcesTableProcessedTableManager =
    ProcessedTableManager<
      _$SessionDatabase,
      $DataEntryIncomeSourcesTable,
      DataEntryIncomeSource,
      $$DataEntryIncomeSourcesTableFilterComposer,
      $$DataEntryIncomeSourcesTableOrderingComposer,
      $$DataEntryIncomeSourcesTableAnnotationComposer,
      $$DataEntryIncomeSourcesTableCreateCompanionBuilder,
      $$DataEntryIncomeSourcesTableUpdateCompanionBuilder,
      (
        DataEntryIncomeSource,
        BaseReferences<
          _$SessionDatabase,
          $DataEntryIncomeSourcesTable,
          DataEntryIncomeSource
        >,
      ),
      DataEntryIncomeSource,
      PrefetchHooks Function()
    >;
typedef $$PendingStageSyncTableCreateCompanionBuilder =
    PendingStageSyncCompanion Function({
      required String stage,
      required DateTime queuedAt,
      Value<int> rowid,
    });
typedef $$PendingStageSyncTableUpdateCompanionBuilder =
    PendingStageSyncCompanion Function({
      Value<String> stage,
      Value<DateTime> queuedAt,
      Value<int> rowid,
    });

class $$PendingStageSyncTableFilterComposer
    extends Composer<_$SessionDatabase, $PendingStageSyncTable> {
  $$PendingStageSyncTableFilterComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnFilters<String> get stage => $composableBuilder(
    column: $table.stage,
    builder: (column) => ColumnFilters(column),
  );

  ColumnFilters<DateTime> get queuedAt => $composableBuilder(
    column: $table.queuedAt,
    builder: (column) => ColumnFilters(column),
  );
}

class $$PendingStageSyncTableOrderingComposer
    extends Composer<_$SessionDatabase, $PendingStageSyncTable> {
  $$PendingStageSyncTableOrderingComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  ColumnOrderings<String> get stage => $composableBuilder(
    column: $table.stage,
    builder: (column) => ColumnOrderings(column),
  );

  ColumnOrderings<DateTime> get queuedAt => $composableBuilder(
    column: $table.queuedAt,
    builder: (column) => ColumnOrderings(column),
  );
}

class $$PendingStageSyncTableAnnotationComposer
    extends Composer<_$SessionDatabase, $PendingStageSyncTable> {
  $$PendingStageSyncTableAnnotationComposer({
    required super.$db,
    required super.$table,
    super.joinBuilder,
    super.$addJoinBuilderToRootComposer,
    super.$removeJoinBuilderFromRootComposer,
  });
  GeneratedColumn<String> get stage =>
      $composableBuilder(column: $table.stage, builder: (column) => column);

  GeneratedColumn<DateTime> get queuedAt =>
      $composableBuilder(column: $table.queuedAt, builder: (column) => column);
}

class $$PendingStageSyncTableTableManager
    extends
        RootTableManager<
          _$SessionDatabase,
          $PendingStageSyncTable,
          PendingStageSyncData,
          $$PendingStageSyncTableFilterComposer,
          $$PendingStageSyncTableOrderingComposer,
          $$PendingStageSyncTableAnnotationComposer,
          $$PendingStageSyncTableCreateCompanionBuilder,
          $$PendingStageSyncTableUpdateCompanionBuilder,
          (
            PendingStageSyncData,
            BaseReferences<
              _$SessionDatabase,
              $PendingStageSyncTable,
              PendingStageSyncData
            >,
          ),
          PendingStageSyncData,
          PrefetchHooks Function()
        > {
  $$PendingStageSyncTableTableManager(
    _$SessionDatabase db,
    $PendingStageSyncTable table,
  ) : super(
        TableManagerState(
          db: db,
          table: table,
          createFilteringComposer: () =>
              $$PendingStageSyncTableFilterComposer($db: db, $table: table),
          createOrderingComposer: () =>
              $$PendingStageSyncTableOrderingComposer($db: db, $table: table),
          createComputedFieldComposer: () =>
              $$PendingStageSyncTableAnnotationComposer($db: db, $table: table),
          updateCompanionCallback:
              ({
                Value<String> stage = const Value.absent(),
                Value<DateTime> queuedAt = const Value.absent(),
                Value<int> rowid = const Value.absent(),
              }) => PendingStageSyncCompanion(
                stage: stage,
                queuedAt: queuedAt,
                rowid: rowid,
              ),
          createCompanionCallback:
              ({
                required String stage,
                required DateTime queuedAt,
                Value<int> rowid = const Value.absent(),
              }) => PendingStageSyncCompanion.insert(
                stage: stage,
                queuedAt: queuedAt,
                rowid: rowid,
              ),
          withReferenceMapper: (p0) => p0
              .map((e) => (e.readTable(table), BaseReferences(db, table, e)))
              .toList(),
          prefetchHooksCallback: null,
        ),
      );
}

typedef $$PendingStageSyncTableProcessedTableManager =
    ProcessedTableManager<
      _$SessionDatabase,
      $PendingStageSyncTable,
      PendingStageSyncData,
      $$PendingStageSyncTableFilterComposer,
      $$PendingStageSyncTableOrderingComposer,
      $$PendingStageSyncTableAnnotationComposer,
      $$PendingStageSyncTableCreateCompanionBuilder,
      $$PendingStageSyncTableUpdateCompanionBuilder,
      (
        PendingStageSyncData,
        BaseReferences<
          _$SessionDatabase,
          $PendingStageSyncTable,
          PendingStageSyncData
        >,
      ),
      PendingStageSyncData,
      PrefetchHooks Function()
    >;

class $SessionDatabaseManager {
  final _$SessionDatabase _db;
  $SessionDatabaseManager(this._db);
  $$PinnedReferenceVersionsTableTableManager get pinnedReferenceVersions =>
      $$PinnedReferenceVersionsTableTableManager(
        _db,
        _db.pinnedReferenceVersions,
      );
  $$LocalDraftTableTableManager get localDraft =>
      $$LocalDraftTableTableManager(_db, _db.localDraft);
  $$LocalProgressTableTableManager get localProgress =>
      $$LocalProgressTableTableManager(_db, _db.localProgress);
  $$DataEntryDraftTableTableManager get dataEntryDraft =>
      $$DataEntryDraftTableTableManager(_db, _db.dataEntryDraft);
  $$DataEntryIncomeSourcesTableTableManager get dataEntryIncomeSources =>
      $$DataEntryIncomeSourcesTableTableManager(
        _db,
        _db.dataEntryIncomeSources,
      );
  $$PendingStageSyncTableTableManager get pendingStageSync =>
      $$PendingStageSyncTableTableManager(_db, _db.pendingStageSync);
}
